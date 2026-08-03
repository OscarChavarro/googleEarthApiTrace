package planetdemviewer.render;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import planetdemviewer.config.Configuration;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileTreeDiscoveryService;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import planetdemviewer.terrain.TerrainSeamStitcher;
import planetdemviewer.terrain.TerrainTilePlan;
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

/** Shader-based renderer for regular DEM triangle meshes. */
public final class Jogl4TerrainRenderer {
    private static final float SURFACE_OFFSET_FACTOR = 1.0f;
    private static final float SURFACE_OFFSET_UNITS = 1.0f;
    private static final float LINE_OFFSET_FACTOR = -1.0f;
    private static final float LINE_OFFSET_UNITS = -1.0f;

    private final TerrainSeamStitcher seamStitcher = new TerrainSeamStitcher();
    private final Map<String, GpuMesh> meshes = new LinkedHashMap<>(64, 0.75f, true);
    private long gpuBytes;

    public void draw(
        GL4 gl,
        PyramidalImageInstance instance,
        Camera cullingCamera,
        Camera renderingCamera,
        double relativeScale,
        double heightExagerationFactor,
        RendererConfiguration quality,
        Light light,
        SimpleMaterial material,
        TileImageLoader loader,
        TileTreeDiscoveryService discoveryService
    ) {
        List<DrawCommand> commands = QuadtreeDrawPlanner.select(instance, cullingCamera, relativeScale);
        for (DrawCommand command : commands) {
            if (discoveryService != null) {
                discoveryService.requestVisible(instance.getImage(), command.node());
            }
        }

        List<TerrainTilePlan> plans = seamStitcher.plan(commands);
        Map<String, CompletableFuture<TriangleMesh>> preparations = new LinkedHashMap<>();
        for (TerrainTilePlan plan : plans) {
            if (!meshes.containsKey(plan.variantKey())) {
                CompletableFuture<TriangleMesh> preparation = seamStitcher.prepare(plan, loader);
                if (preparation != null) {
                    preparations.put(plan.variantKey(), preparation);
                }
            }
        }

        for (TerrainTilePlan plan : plans) {
            GpuMesh mesh = meshes.get(plan.variantKey());
            if (mesh == null) {
                CompletableFuture<TriangleMesh> preparation = preparations.get(plan.variantKey());
                if (preparation == null) {
                    continue;
                }
                try {
                    mesh = acquire(gl, plan.variantKey(), preparation.join());
                }
                catch (RuntimeException ignored) {
                    // Keep rendering other tiles; a later frame may retry after cache eviction.
                    seamStitcher.releasePreparedMesh(plan.variantKey());
                    continue;
                }
                seamStitcher.releasePreparedMesh(plan.variantKey());
            }
            Matrix4x4d modelMatrix = new Matrix4x4d()
                .translation(instance.getOffsetX(), instance.getOffsetY(), instance.getZOffset() * 1e-4)
                .multiply(new Matrix4x4d().scale(
                    relativeScale,
                    relativeScale,
                    relativeScale * heightExagerationFactor));
            drawMesh(gl, mesh, renderingCamera, modelMatrix, quality, light, material);
        }
    }

    private void drawMesh(GL4 gl, GpuMesh mesh, Camera camera, Matrix4x4d modelMatrix,
                          RendererConfiguration quality, Light light, SimpleMaterial material) {
        if (mesh.indexCount == 0) {
            return;
        }
        Matrix4x4d mvp = camera.calculateProjectionMatrix().multiply(modelMatrix);
        Matrix4x4d modelIt = modelMatrix.invert().transpose();

        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glDepthFunc(GL.GL_LESS);
        gl.glDisable(GL.GL_CULL_FACE);

        if (quality.isSurfacesSet()) {
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
            RendererConfiguration wireQuality = noLightConfiguration();
            int program = Jogl4RendererConfigurationShaderSelector.selectSurfaceShaderProgram(
                gl, wireQuality, false, false);
            configureProgram(gl, program, mvp, modelMatrix, modelIt, camera, light,
                wireMaterial(quality.getWireColor()), wireQuality);
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
            RendererConfiguration pointQuality = noLightConfiguration();
            int program = Jogl4RendererConfigurationShaderSelector.selectSurfaceShaderProgram(
                gl, pointQuality, false, false);
            configureProgram(gl, program, mvp, modelMatrix, modelIt, camera, light,
                pointMaterial(), pointQuality);
            gl.glPolygonMode(GL.GL_FRONT_AND_BACK, GL4.GL_FILL);
            gl.glPointSize(3.0f);
            gl.glDepthMask(false);
            gl.glDepthFunc(GL.GL_LEQUAL);
            gl.glBindVertexArray(mesh.vaoId);
            gl.glDrawArrays(GL.GL_POINTS, 0, mesh.vertexCount);
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

    private GpuMesh acquire(GL4 gl, String key, TriangleMesh generated) {
        GpuMesh cached = meshes.get(key);
        if (cached != null) {
            return cached;
        }
        GpuMesh uploaded = upload(gl, generated);
        meshes.put(key, uploaded);
        gpuBytes += uploaded.byteCount;
        evictToBudget(gl);
        return uploaded;
    }

    private static GpuMesh upload(GL4 gl, TriangleMesh mesh) {
        double[] sourcePositions = mesh.getVertexPositions();
        double[] sourceNormals = mesh.getVertexNormals();
        int[] indices = mesh.getTriangleIndexes();
        float[] positions = toFloatArray(sourcePositions);
        float[] normals = toFloatArray(sourceNormals);

        int[] vao = new int[1];
        int[] buffers = new int[3];
        gl.glGenVertexArrays(1, vao, 0);
        gl.glGenBuffers(3, buffers, 0);
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

        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[2]);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) indices.length * Integer.BYTES,
            intBuffer(indices), GL.GL_STATIC_DRAW);

        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        long bytes = (long) (positions.length + normals.length) * Float.BYTES
            + (long) indices.length * Integer.BYTES;
        return new GpuMesh(vao[0], buffers[0], buffers[1], buffers[2],
            sourcePositions.length / 3, indices.length, bytes);
    }

    private void evictToBudget(GL4 gl) {
        Iterator<Map.Entry<String, GpuMesh>> iterator = meshes.entrySet().iterator();
        while (gpuBytes > Configuration.MAX_GPU_TEXTURE_MEMORY && meshes.size() > 1 && iterator.hasNext()) {
            GpuMesh oldest = iterator.next().getValue();
            iterator.remove();
            delete(gl, oldest);
            gpuBytes -= oldest.byteCount;
        }
    }

    public void dispose(GL4 gl) {
        for (GpuMesh mesh : meshes.values()) {
            delete(gl, mesh);
        }
        meshes.clear();
        gpuBytes = 0L;
        seamStitcher.shutdown();
        Jogl4RendererConfigurationShaderSelector.dispose(gl);
    }

    public int getResidentMeshCount() {
        return meshes.size();
    }

    public long getGpuBytesAssigned() {
        return gpuBytes;
    }

    private static void delete(GL4 gl, GpuMesh mesh) {
        gl.glDeleteBuffers(3, new int[] {mesh.positionVboId, mesh.normalVboId, mesh.indexBufferId}, 0);
        gl.glDeleteVertexArrays(1, new int[] {mesh.vaoId}, 0);
    }

    private static RendererConfiguration noLightConfiguration() {
        RendererConfiguration configuration = new RendererConfiguration();
        configuration.setTexture(false);
        configuration.setBumpMap(false);
        configuration.setShadingType(RendererConfiguration.SHADING_TYPE_NOLIGHT);
        return configuration;
    }

    private static SimpleMaterial wireMaterial(ColorRgb color) {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.0, 0.0, 0.0));
        material = material.withDiffuse(color == null ? new ColorRgb(1.0, 1.0, 1.0) : color);
        return material.withSpecular(new ColorRgb(0.0, 0.0, 0.0));
    }

    private static SimpleMaterial pointMaterial() {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.0, 0.0, 0.0));
        material = material.withDiffuse(new ColorRgb(1.0, 0.1, 0.1));
        return material.withSpecular(new ColorRgb(0.0, 0.0, 0.0));
    }

    private static float[] toFloatArray(double[] source) {
        float[] result = new float[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (float) source[i];
        }
        return result;
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

    private record GpuMesh(int vaoId, int positionVboId, int normalVboId,
                           int indexBufferId, int vertexCount, int indexCount, long byteCount) {
    }
}
