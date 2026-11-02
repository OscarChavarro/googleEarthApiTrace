package planetdemviewer.render.jogl4;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.render.PlanetViewerSession;
import planetdemviewer.render.RenderStyles;
import planetdemviewer.render.TerrainDrawItem;
import planetdemviewer.render.TerrainFramePlanner;
import planetdemviewer.render.TerrainFrameSettings;
import planetdemviewer.render.TerrainMeshBuffers;
import planetdemviewer.render.TerrainPickFrame;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;
import vsdk.toolkit.environment.light.Light;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;
import vsdk.toolkit.render.jogl.Jogl4MatrixRenderer;
import vsdk.toolkit.render.jogl.Jogl4RendererConfigurationShaderSelector;
import vsdk.toolkit.render.jogl.Jogl4ShaderProgramUtil;

/** Shader-based renderer for regular DEM triangle meshes. */
final class Jogl4TerrainRenderer {
    private static final float SURFACE_OFFSET_FACTOR = 1.0f;
    private static final float SURFACE_OFFSET_UNITS = 1.0f;
    private static final float LINE_OFFSET_FACTOR = -1.0f;
    private static final float LINE_OFFSET_UNITS = -1.0f;
    private static final String BOUNDS_VERTEX_SHADER = """
        #version 410 core
        layout(location = 0) in vec3 position;
        uniform mat4 mvp;
        void main() {
            gl_Position = mvp * vec4(position, 1.0);
        }
        """;
    private static final String TEXTURE_VERTEX_SHADER = """
        #version 410 core
        layout(location=0) in vec3 position;
        layout(location=1) in vec3 normal;
        layout(location=2) in vec2 texCoord;
        uniform mat4 mvp;
        uniform mat4 model;
        uniform mat4 modelInverseTranspose;
        out vec2 uv;
        out vec3 surfacePosition;
        out vec3 surfaceNormal;
        void main() {
            gl_Position=mvp*vec4(position,1.0);
            uv=texCoord;
            surfacePosition=(model*vec4(position,1.0)).xyz;
            surfaceNormal=normalize(mat3(modelInverseTranspose)*normal);
        }
        """;
    private static final String TEXTURE_FRAGMENT_SHADER = """
        #version 410 core
        in vec2 uv;
        in vec3 surfacePosition;
        in vec3 surfaceNormal;
        uniform sampler2D tileTexture;
        uniform vec3 lightPosition;
        uniform vec3 lightColor;
        uniform vec3 ambientColor;
        uniform vec3 cameraPosition;
        uniform vec3 specularColor;
        uniform float phongExponent;
        out vec4 fragColor;
        void main() {
            vec3 albedo=texture(tileTexture, uv).rgb;
            vec3 lightDirection=normalize(lightPosition-surfacePosition);
            vec3 normal=normalize(surfaceNormal);
            float lambert=max(dot(normal, lightDirection), 0.0);
            vec3 viewDirection=normalize(cameraPosition-surfacePosition);
            vec3 reflectedLight=reflect(-lightDirection, normal);
            float specular=lambert > 0.0
                ? pow(max(dot(viewDirection, reflectedLight), 0.0), phongExponent)
                : 0.0;
            vec3 color=albedo*(ambientColor+lightColor*lambert)
                + specularColor*lightColor*specular;
            fragColor=vec4(color, 1.0);
        }
        """;
    private static final String BOUNDS_FRAGMENT_SHADER = """
        #version 410 core
        layout(location = 0) out vec4 fragColor;
        uniform vec3 color;
        void main() {
            fragColor = vec4(color, 1.0);
        }
        """;

    private final TerrainFramePlanner<Integer, GpuMesh> planner;
    private final TerrainPickFrame pickFrame;
    private int boundsProgram;
    private int boundsMvpLocation;
    private int boundsColorLocation;
    private int textureProgram;
    private int textureMvpLocation;
    private IntBuffer indexScratch;

    Jogl4TerrainRenderer(PlanetViewerSession session) {
        this.planner = session.newTerrainFramePlanner();
        this.pickFrame = session.pickFrame();
    }

    List<DrawCommand> draw(GL4 gl, PyramidalImageInstance instance, TerrainFrameSettings settings,
                           Camera renderingCamera, RendererConfiguration quality, Light light,
                           SimpleMaterial material) {
        Jogl4TextureCacheHelper gpu = new Jogl4TextureCacheHelper(gl);
        List<DrawCommand> drawnCommands = new ArrayList<>();
        List<TerrainDrawItem> items = planner.plan(instance, settings);
        for (TerrainDrawItem item : items) {
            if (planner.residentMesh(item.variantKey()) == null && item.preparation() != null) {
                try {
                    acquire(gl, item);
                }
                catch (RuntimeException ignored) {
                    // Keep rendering other tiles; a later frame may retry after cache eviction.
                }
                finally {
                    planner.releasePreparedMesh(item.variantKey());
                }
            }
        }
        // Looked up after every upload, since an upload may evict a mesh of this same frame.
        List<TerrainDrawItem> drawnItems = new ArrayList<>(items.size());
        List<GpuMesh> drawnMeshes = new ArrayList<>(items.size());
        List<TriangleMesh> cpuMeshes = new ArrayList<>(items.size());
        for (TerrainDrawItem item : items) {
            GpuMesh mesh = planner.residentMesh(item.variantKey());
            if (mesh != null) {
                drawnItems.add(item);
                drawnMeshes.add(mesh);
                cpuMeshes.add(mesh.cpuMesh);
            }
        }
        planner.updateTriangles(drawnItems, cpuMeshes, settings, renderingCamera);
        for (int i = 0; i < drawnItems.size(); i++) {
            TerrainDrawItem item = drawnItems.get(i);
            GpuMesh mesh = drawnMeshes.get(i);
            int[] changedTriangles = planner.trianglesToUpload(mesh.cpuMesh);
            if (changedTriangles != null) {
                replaceTriangles(gl, mesh, changedTriangles);
            }
            Matrix4x4d modelMatrix = item.modelMatrix();
            Integer imagery = planner.acquireImagery(item, gpu);
            drawMesh(gl, mesh, renderingCamera, modelMatrix, quality, light, material, imagery);
            drawnCommands.add(item.command());
            pickFrame.meshDrawn(item, mesh.cpuMesh);
            float[] color = item.boundsColor();
            if (color != null) {
                drawBounds(gl, mesh.bounds, renderingCamera.calculateProjectionMatrix().multiply(modelMatrix),
                    color[0], color[1], color[2]);
            }
        }
        return List.copyOf(drawnCommands);
    }

    /** Draws a pre-uploaded min/max box with a constant flat color. */
    private void drawBounds(GL4 gl, GpuBounds bounds, Matrix4x4d mvp, float red, float green, float blue) {
        if (bounds == null) {
            return;
        }
        ensureBoundsProgram(gl);
        gl.glUseProgram(boundsProgram);
        gl.glUniformMatrix4fv(boundsMvpLocation, 1, false,
            Jogl4MatrixRenderer.toColumnMajorFloatArray(mvp), 0);
        gl.glUniform3f(boundsColorLocation, red, green, blue);
        gl.glBindVertexArray(bounds.vaoId);
        gl.glLineWidth(1.0f);
        gl.glDrawArrays(GL.GL_LINES, 0, TerrainMeshBuffers.BOUNDS_LINE_VERTICES);
        gl.glBindVertexArray(0);
        gl.glUseProgram(0);
    }

    void releaseImagerySubtree(GL4 gl, TileAddress subtree) {
        planner.releaseImagerySubtree(subtree, new Jogl4TextureCacheHelper(gl));
    }

    private void drawMesh(GL4 gl, GpuMesh mesh, Camera camera, Matrix4x4d modelMatrix,
                          RendererConfiguration quality, Light light, SimpleMaterial material, Integer imagery) {
        if (mesh.indexCount == 0) {
            return;
        }
        Matrix4x4d mvp = camera.calculateProjectionMatrix().multiply(modelMatrix);
        Matrix4x4d modelIt = modelMatrix.invert().transpose();

        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LESS);
        gl.glDisable(GL.GL_CULL_FACE);

        if (imagery != null && quality.isSurfacesSet()) {
            ensureTextureProgram(gl);
            gl.glUseProgram(textureProgram);
            gl.glUniformMatrix4fv(textureMvpLocation, 1, false, Jogl4MatrixRenderer.toColumnMajorFloatArray(mvp), 0);
            setMatrix(gl, textureProgram, "model", modelMatrix);
            setMatrix(gl, textureProgram, "modelInverseTranspose", modelIt);
            setVector3(gl, textureProgram, "lightPosition", light.getPosition());
            setVector3(gl, textureProgram, "lightColor", light.getEmission());
            setVector3(gl, textureProgram, "ambientColor", material.getAmbient());
            setVector3(gl, textureProgram, "cameraPosition", camera.getPosition());
            setVector3(gl, textureProgram, "specularColor", material.getSpecular());
            setFloat(gl, textureProgram, "phongExponent", (float) material.getPhongExponent());
            gl.glActiveTexture(GL.GL_TEXTURE0); gl.glBindTexture(GL.GL_TEXTURE_2D, imagery);
            int sampler = gl.glGetUniformLocation(textureProgram, "tileTexture"); if (sampler >= 0) gl.glUniform1i(sampler, 0);
            renderTriangles(gl, mesh); gl.glUseProgram(0);
        }
        else if (quality.isSurfacesSet()) {
            int program = Jogl4RendererConfigurationShaderSelector.selectSurfaceShaderProgram(
                gl, quality, false, false);
            configureProgram(gl, program, mvp, modelMatrix, modelIt, camera, light, material, quality);
            gl.glEnable(GL.GL_POLYGON_OFFSET_FILL);
            gl.glPolygonOffset(SURFACE_OFFSET_FACTOR, SURFACE_OFFSET_UNITS);
            gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL4.GL_FILL);
            gl.glDepthMask(true);
            renderTriangles(gl, mesh);
            gl.glDisable(GL.GL_POLYGON_OFFSET_FILL);
            Jogl4RendererConfigurationShaderSelector.deactivateShader(gl);
        }

        if (quality.isWiresSet()) {
            RendererConfiguration wireQuality = RenderStyles.noLightConfiguration();
            int program = Jogl4RendererConfigurationShaderSelector.selectSurfaceShaderProgram(
                gl, wireQuality, false, false);
            configureProgram(gl, program, mvp, modelMatrix, modelIt, camera, light,
                RenderStyles.wireMaterial(quality.getWireColor()), wireQuality);
            gl.glEnable(GL4.GL_POLYGON_OFFSET_LINE);
            gl.glPolygonOffset(LINE_OFFSET_FACTOR, LINE_OFFSET_UNITS);
            gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL4.GL_LINE);
            gl.glDepthMask(false);
            gl.glDepthFunc(GL.GL_LEQUAL);
            renderTriangles(gl, mesh);
            gl.glDisable(GL4.GL_POLYGON_OFFSET_LINE);
            Jogl4RendererConfigurationShaderSelector.deactivateShader(gl);
        }

        if (quality.isPointsSet()) {
            RendererConfiguration pointQuality = RenderStyles.noLightConfiguration();
            int program = Jogl4RendererConfigurationShaderSelector.selectSurfaceShaderProgram(
                gl, pointQuality, false, false);
            configureProgram(gl, program, mvp, modelMatrix, modelIt, camera, light,
                RenderStyles.pointMaterial(), pointQuality);
            gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL4.GL_FILL);
            gl.glPointSize(3.0f);
            gl.glDepthMask(false);
            gl.glDepthFunc(GL.GL_LEQUAL);
            gl.glBindVertexArray(mesh.vaoId);
            gl.glDrawElements(GL.GL_POINTS, mesh.indexCount, GL.GL_UNSIGNED_INT, 0L);
            gl.glBindVertexArray(0);
            Jogl4RendererConfigurationShaderSelector.deactivateShader(gl);
        }

        gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL4.GL_FILL);
        gl.glDepthMask(true);
        gl.glDepthFunc(GL.GL_LESS);
    }

    private static void renderTriangles(GL4 gl, GpuMesh mesh) {
        gl.glBindVertexArray(mesh.vaoId);
        gl.glDrawElements(GL.GL_TRIANGLES, mesh.indexCount, GL.GL_UNSIGNED_INT, 0L);
        gl.glBindVertexArray(0);
    }

    private void configureProgram(GL4 gl, int program, Matrix4x4d mvp, Matrix4x4d model,
                                  Matrix4x4d modelIt, Camera camera, Light light,
                                  SimpleMaterial material, RendererConfiguration quality) {
        ColorRgb diffuse = material.getDiffuse();
        Jogl4RendererConfigurationShaderSelector.activateShader(
            gl, program, mvp, quality,
            (float) diffuse.r(), (float) diffuse.g(), (float) diffuse.b());
        setMatrix(gl, program, "modelViewLocal", model);
        setMatrix(gl, program, "modelViewITLocal", modelIt);
        setVector3(gl, program, "cameraPositionGlobal", camera.getPosition());
        setVector3(gl, program, "lightPositionsGlobal[0]", light.getPosition());
        setVector3(gl, program, "lightColorsGlobal[0]", light.getEmission());
        setInt(gl, program, "numberOfLights", 1);
        setVector3(gl, program, "ambientColor", material.getAmbient());
        setVector3(gl, program, "diffuseColor", material.getDiffuse());
        setVector3(gl, program, "specularColor", material.getSpecular());
        setFloat(gl, program, "phongExponent", (float) material.getPhongExponent());
        setInt(gl, program, "withTexture", 0);
        setInt(gl, program, "withBumpMap", 0);
    }

    private GpuMesh acquire(GL4 gl, TerrainDrawItem item) {
        TriangleMesh generated = item.preparation().join();
        TerrainMeshBuffers source = TerrainMeshBuffers.of(generated, item.command().node());
        GpuMesh uploaded = upload(gl, source, generated);
        for (GpuMesh evicted : planner.meshUploaded(item.variantKey(), uploaded, source.byteSize())) {
            delete(gl, evicted);
        }
        return uploaded;
    }

    private void replaceTriangles(GL4 gl, GpuMesh mesh, int[] indices) {
        if (indexScratch == null || indexScratch.capacity() < indices.length) {
            indexScratch = Buffers.newDirectIntBuffer(indices.length);
        }
        indexScratch.clear();
        indexScratch.put(indices).flip();
        gl.glBindVertexArray(mesh.vaoId);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) indices.length * Integer.BYTES,
            indexScratch, GL.GL_DYNAMIC_DRAW);
        gl.glBindVertexArray(0);
        mesh.indexCount = indices.length;
    }

    private static GpuMesh upload(GL4 gl, TerrainMeshBuffers source, TriangleMesh mesh) {
        float[] positions = source.positions();
        float[] normals = source.normals();
        float[] uvs = source.textureCoordinates();
        int[] indices = source.indices();

        int[] vao = new int[1];
        int[] buffers = new int[4];
        gl.glGenVertexArrays(1, vao, 0);
        gl.glGenBuffers(4, buffers, 0);
        gl.glBindVertexArray(vao[0]);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[0]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) positions.length * Float.BYTES,
            floatBuffer(positions), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0L);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[1]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) normals.length * Float.BYTES,
            floatBuffer(normals), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, 0, 0L);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[2]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) uvs.length * Float.BYTES, floatBuffer(uvs), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, 0, 0L);

        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[3]);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) indices.length * Integer.BYTES,
            intBuffer(indices), GL.GL_STATIC_DRAW);

        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        GpuBounds bounds = uploadBounds(gl, source.boundsLines());
        return new GpuMesh(vao[0], buffers[0], buffers[1], buffers[2], buffers[3],
            indices.length, bounds, mesh);
    }

    private static GpuBounds uploadBounds(GL4 gl, float[] positions) {
        int[] vao = new int[1];
        int[] vbo = new int[1];
        gl.glGenVertexArrays(1, vao, 0);
        gl.glGenBuffers(1, vbo, 0);
        gl.glBindVertexArray(vao[0]);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) positions.length * Float.BYTES,
            floatBuffer(positions), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0L);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        return new GpuBounds(vao[0], vbo[0]);
    }

    void dispose(GL4 gl) {
        planner.clearMeshes().forEach(mesh -> delete(gl, mesh));
        planner.shutdown();
        if (boundsProgram != 0) {
            gl.glDeleteProgram(boundsProgram);
            boundsProgram = 0;
            boundsMvpLocation = -1;
        }
        if (textureProgram != 0) { gl.glDeleteProgram(textureProgram); textureProgram = 0; }
        planner.clearImagery(new Jogl4TextureCacheHelper(gl));
        Jogl4RendererConfigurationShaderSelector.dispose(gl);
    }

    private static void delete(GL4 gl, GpuMesh mesh) {
        gl.glDeleteBuffers(4, new int[] {mesh.positionVboId, mesh.normalVboId, mesh.uvVboId, mesh.indexBufferId}, 0);
        gl.glDeleteVertexArrays(1, new int[] {mesh.vaoId}, 0);
        gl.glDeleteBuffers(1, new int[] {mesh.bounds.vboId}, 0);
        gl.glDeleteVertexArrays(1, new int[] {mesh.bounds.vaoId}, 0);
    }

    private void ensureBoundsProgram(GL4 gl) {
        if (boundsProgram != 0) {
            return;
        }
        boundsProgram = Jogl4ShaderProgramUtil.createProgramFromSources(
            gl, BOUNDS_VERTEX_SHADER, BOUNDS_FRAGMENT_SHADER);
        boundsMvpLocation = gl.glGetUniformLocation(boundsProgram, "mvp");
        boundsColorLocation = gl.glGetUniformLocation(boundsProgram, "color");
    }
    private void ensureTextureProgram(GL4 gl) {
        if (textureProgram != 0) return;
        textureProgram = Jogl4ShaderProgramUtil.createProgramFromSources(gl, TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER);
        textureMvpLocation = gl.glGetUniformLocation(textureProgram, "mvp");
    }

    private static FloatBuffer floatBuffer(float[] data) {
        FloatBuffer buffer = Buffers.newDirectFloatBuffer(data.length);
        buffer.put(data).flip();
        return buffer;
    }

    private static IntBuffer intBuffer(int[] data) {
        IntBuffer buffer = Buffers.newDirectIntBuffer(data.length);
        buffer.put(data).flip();
        return buffer;
    }

    private static void setMatrix(GL4 gl, int program, String name, Matrix4x4d value) {
        int location = gl.glGetUniformLocation(program, name);
        if (location >= 0) {
            gl.glUniformMatrix4fv(location, 1, false,
                Jogl4MatrixRenderer.toColumnMajorFloatArray(value), 0);
        }
    }

    private static void setVector3(GL4 gl, int program, String name, Vector3Dd value) {
        int location = gl.glGetUniformLocation(program, name);
        if (location >= 0) {
            gl.glUniform3f(location, (float) value.x(), (float) value.y(), (float) value.z());
        }
    }

    private static void setVector3(GL4 gl, int program, String name, ColorRgb value) {
        int location = gl.glGetUniformLocation(program, name);
        if (location >= 0) {
            gl.glUniform3f(location, (float) value.r(), (float) value.g(), (float) value.b());
        }
    }

    private static void setInt(GL4 gl, int program, String name, int value) {
        int location = gl.glGetUniformLocation(program, name);
        if (location >= 0) {
            gl.glUniform1i(location, value);
        }
    }

    private static void setFloat(GL4 gl, int program, String name, float value) {
        int location = gl.glGetUniformLocation(program, name);
        if (location >= 0) {
            gl.glUniform1f(location, value);
        }
    }

    private static final class GpuMesh {
        private final int vaoId;
        private final int positionVboId;
        private final int normalVboId;
        private final int uvVboId;
        private final int indexBufferId;
        private int indexCount;
        private final GpuBounds bounds;
        private final TriangleMesh cpuMesh;

        private GpuMesh(int vaoId, int positionVboId, int normalVboId, int uvVboId, int indexBufferId,
                        int indexCount, GpuBounds bounds, TriangleMesh cpuMesh) {
            this.vaoId = vaoId;
            this.positionVboId = positionVboId;
            this.normalVboId = normalVboId;
            this.uvVboId = uvVboId;
            this.indexBufferId = indexBufferId;
            this.indexCount = indexCount;
            this.bounds = bounds;
            this.cpuMesh = cpuMesh;
        }
    }

    private record GpuBounds(int vaoId, int vboId) {
    }
}
