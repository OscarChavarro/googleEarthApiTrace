package planetdemviewer.render;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.texture.Texture;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import planetdemviewer.config.Configuration;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileTreeDiscoveryService;
import planetdemviewer.io.imagery.ImageryTileLoader;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import planetdemviewer.terrain.TerrainSeamStitcher;
import planetdemviewer.terrain.TerrainSubdivisionScheme;
import planetdemviewer.terrain.TerrainMeshPlan;
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
public final class Jogl4TerrainRenderer {
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

    private final TerrainSeamStitcher seamStitcher = new TerrainSeamStitcher();
    /**
     * Access-order LRU keyed by the complete seam variant, not just the tile.
     * A border stitched against a coarse neighbour must never replace a mesh
     * generated for the same tile under a different LOD neighbourhood.
     */
    private final Map<String, GpuMesh> meshes = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, Long> meshLastViewedNanos = new LinkedHashMap<>();
    private final QuadtreeDrawPlanner.LodState lodState = new QuadtreeDrawPlanner.LodState();
    private int boundsProgram;
    private int boundsMvpLocation;
    private int boundsColorLocation;
    private int textureProgram;
    private int textureMvpLocation;
    private long gpuBytes;
    private final List<PickableMesh> pickableMeshes = new ArrayList<>();

    /** Starts a new visible-terrain snapshot for CPU picking on the next frame. */
    public void beginPickFrame() {
        pickableMeshes.clear();
    }

    public List<DrawCommand> draw(
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
        TileTreeDiscoveryService discoveryService,
        boolean tileBoundsVisible,
        boolean uniformLod, ImageryTileLoader imageryLoader, boolean imageryEnabled,
        TileAddress selectedTile,
        TerrainSubdivisionScheme subdivisionScheme
    ) {
        List<DrawCommand> commands = uniformLod
            ? QuadtreeDrawPlanner.selectUniformLod(instance, cullingCamera, relativeScale)
            : QuadtreeDrawPlanner.selectTerrain(instance, cullingCamera, relativeScale,
                heightExagerationFactor, loader, lodState);
        for (DrawCommand command : commands) {
            if (discoveryService != null) {
                discoveryService.requestVisible(instance.getImage(),
                    command.node().nearestSelfOrAncestorWithTile());
            }
        }

        List<TerrainMeshPlan> plans = seamStitcher.plan(commands, subdivisionScheme);
        List<DrawCommand> drawnCommands = new ArrayList<>();
        Map<String, CompletableFuture<TriangleMesh>> preparations = new LinkedHashMap<>();
        for (TerrainMeshPlan plan : plans) {
            if (!meshes.containsKey(plan.variantKey())) {
                CompletableFuture<TriangleMesh> preparation = seamStitcher.prepare(plan, loader);
                if (preparation != null) {
                    preparations.put(plan.variantKey(), preparation);
                }
            }
        }

        for (TerrainMeshPlan plan : plans) {
            // A resident GPU mesh still represents a visible DEM page. Renew the
            // source page's LRU timestamp even when no mesh preparation is needed.
            if (loader != null) {
                loader.peekElevation(plan.command().node().getTileFile());
            }
            GpuMesh mesh = meshes.get(plan.variantKey());
            if (mesh != null) {
                meshLastViewedNanos.put(plan.variantKey(), System.nanoTime());
            }
            if (mesh == null) {
                CompletableFuture<TriangleMesh> preparation = preparations.get(plan.variantKey());
                if (preparation == null) {
                    continue;
                }
                try {
                    mesh = acquire(gl, plan.variantKey(), preparation.join(), plan.command().node());
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
            Texture imagery = imageryEnabled && imageryLoader != null
                ? imageryTextureCache.acquire(gl, TileAddress.fabdem(plan.command().node().getId()), imageryLoader) : null;
            drawMesh(gl, mesh, renderingCamera, modelMatrix, quality, light, material, imagery);
            drawnCommands.add(plan.command());
            pickableMeshes.add(new PickableMesh(plan.command(), mesh.cpuMesh, modelMatrix,
                instance.getOffsetX(), instance.getOffsetY(), instance.getZOffset() * 1e-4,
                relativeScale, heightExagerationFactor));
            boolean selected = selectedTile != null
                && selectedTile.quadkey().equals(plan.command().node().getId());
            if (tileBoundsVisible || selected) {
                drawBounds(gl, mesh.bounds, renderingCamera.calculateProjectionMatrix().multiply(modelMatrix),
                    1.0f, 1.0f, selected ? 0.0f : 1.0f);
            }
        }
        return List.copyOf(drawnCommands);
    }

    /** Intersects the last rendered terrain frontier using its actual triangle meshes. */
    public TerrainPickHit intersectVisibleTerrain(Ray3 ray) {
        TerrainPickHit closest = null;
        for (PickableMesh pickable : pickableMeshes) {
            double[] positions = pickable.mesh.getVertexPositions();
            int[] indices = pickable.mesh.getTriangleIndexes();
            for (int offset = 0; offset < indices.length; offset += 3) {
                WorldPosition a = transformed(positions, indices[offset], pickable.modelMatrix);
                WorldPosition b = transformed(positions, indices[offset + 1], pickable.modelMatrix);
                WorldPosition c = transformed(positions, indices[offset + 2], pickable.modelMatrix);
                double distance = rayTriangleDistance(ray, a, b, c);
                if (Double.isFinite(distance) && (closest == null || distance < closest.distanceMetres)) {
                    WorldPosition world = pointOnRay(ray, distance);
                    double localX = (world.x() - pickable.offsetX) / pickable.relativeScale + 0.5;
                    double localY = (world.y() - pickable.offsetY) / pickable.relativeScale + 0.5;
                    double elevationMetres = (world.z() - pickable.offsetZ)
                        / (pickable.relativeScale * pickable.heightExaggeration)
                        * Configuration.WORLD_WIDTH_METRES;
                    closest = new TerrainPickHit(pickable.command, world, distance, localX, localY,
                        elevationMetres);
                }
            }
        }
        return closest;
    }

    private static WorldPosition transformed(double[] positions, int vertex, Matrix4x4d matrix) {
        int offset = vertex * 3;
        Vector3Dd result = matrix.multiply(new Vector3Dd(
            positions[offset], positions[offset + 1], positions[offset + 2]));
        return new WorldPosition(result.x(), result.y(), result.z());
    }

    private static double rayTriangleDistance(Ray3 ray, WorldPosition a, WorldPosition b, WorldPosition c) {
        WorldPosition edge1 = subtract(b, a);
        WorldPosition edge2 = subtract(c, a);
        WorldPosition p = cross(ray.direction(), edge2);
        double determinant = dot(edge1, p);
        if (Math.abs(determinant) < 1e-12) {
            return Double.NaN;
        }
        double inverse = 1.0 / determinant;
        WorldPosition t = subtract(ray.origin(), a);
        double u = dot(t, p) * inverse;
        if (u < 0.0 || u > 1.0) {
            return Double.NaN;
        }
        WorldPosition q = cross(t, edge1);
        double v = dot(ray.direction(), q) * inverse;
        if (v < 0.0 || u + v > 1.0) {
            return Double.NaN;
        }
        double distance = dot(edge2, q) * inverse;
        return distance >= 0.0 ? distance : Double.NaN;
    }

    private static WorldPosition pointOnRay(Ray3 ray, double distance) {
        return new WorldPosition(ray.origin().x() + ray.direction().x() * distance,
            ray.origin().y() + ray.direction().y() * distance,
            ray.origin().z() + ray.direction().z() * distance);
    }

    private static WorldPosition subtract(WorldPosition first, WorldPosition second) {
        return new WorldPosition(first.x() - second.x(), first.y() - second.y(), first.z() - second.z());
    }

    private static WorldPosition cross(WorldPosition first, WorldPosition second) {
        return new WorldPosition(first.y() * second.z() - first.z() * second.y(),
            first.z() * second.x() - first.x() * second.z(),
            first.x() * second.y() - first.y() * second.x());
    }

    private static double dot(WorldPosition first, WorldPosition second) {
        return first.x() * second.x() + first.y() * second.y() + first.z() * second.z();
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
        gl.glDrawArrays(GL.GL_LINES, 0, 24);
        gl.glBindVertexArray(0);
        gl.glUseProgram(0);
    }

    private final ImageryTextureCache imageryTextureCache = new ImageryTextureCache();

    public void releaseImagerySubtree(GL4 gl, TileAddress subtree) {
        imageryTextureCache.release(gl, subtree);
    }

    private void drawMesh(GL4 gl, GpuMesh mesh, Camera camera, Matrix4x4d modelMatrix,
                          RendererConfiguration quality, Light light, SimpleMaterial material, Texture imagery) {
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
            gl.glActiveTexture(GL.GL_TEXTURE0); imagery.bind(gl);
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

    private GpuMesh acquire(GL4 gl, String key, TriangleMesh generated, QuadtreeNode node) {
        GpuMesh cached = meshes.get(key);
        if (cached != null) {
            meshLastViewedNanos.put(key, System.nanoTime());
            return cached;
        }
        GpuMesh uploaded = upload(gl, generated, node);
        meshes.put(key, uploaded);
        meshLastViewedNanos.put(key, System.nanoTime());
        gpuBytes += uploaded.byteCount;
        evictToBudget(gl);
        return uploaded;
    }

    private static GpuMesh upload(GL4 gl, TriangleMesh mesh, QuadtreeNode node) {
        double[] sourcePositions = mesh.getVertexPositions();
        double[] sourceNormals = mesh.getVertexNormals();
        int[] indices = mesh.getTriangleIndexes();
        float[] positions = toFloatArray(sourcePositions);
        float[] normals = toFloatArray(sourceNormals);

        int[] vao = new int[1];
        float[] uvs = textureCoordinatesFor(sourcePositions, node);
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
        GpuBounds bounds = uploadBounds(gl, Bounds.fromPositions(sourcePositions));
        long bytes = (long) (positions.length + normals.length + uvs.length) * Float.BYTES
            + (long) indices.length * Integer.BYTES + bounds.byteCount;
        return new GpuMesh(vao[0], buffers[0], buffers[1], buffers[2], buffers[3],
            sourcePositions.length / 3, indices.length, bytes,
            bounds, mesh);
    }

    /**
     * Maps the exact square terrain vertex grid to the corresponding PNG.
     * Edge vertices have s/t values 0 and 1 exactly.  This must not be
     * reconstructed from global positions: LOD edge stitching can move those
     * positions beyond the logical tile.  PNG row zero is its top edge.
     */
    static float[] textureCoordinatesFor(double[] positions, QuadtreeNode node) {
        if (positions == null || positions.length % 3 != 0 || node == null) {
            throw new IllegalArgumentException("Mesh positions and tile node are required");
        }
        int vertexCount = positions.length / 3;
        int gridSize = (int) Math.round(Math.sqrt(vertexCount));
        if (gridSize < 2 || gridSize * gridSize != vertexCount) {
            throw new IllegalArgumentException("Terrain imagery requires a square vertex grid");
        }
        float[] uvs = new float[vertexCount * 2];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int row = vertex / gridSize;
            int column = vertex % gridSize;
            int uvOffset = vertex * 2;
            uvs[uvOffset] = (float) column / (gridSize - 1);
            uvs[uvOffset + 1] = (float) row / (gridSize - 1);
        }
        return uvs;
    }

    /** Computes a tile's min/max strictly from its vertex array, before GPU upload. */
    private static GpuBounds uploadBounds(GL4 gl, Bounds bounds) {
        float[] positions = bounds.linePositions();
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
        return new GpuBounds(vao[0], vbo[0], (long) positions.length * Float.BYTES);
    }

    private void evictToBudget(GL4 gl) {
        Iterator<Map.Entry<String, GpuMesh>> iterator = meshes.entrySet().iterator();
        while (gpuBytes > Configuration.MAX_GPU_TEXTURE_MEMORY && meshes.size() > 1 && iterator.hasNext()) {
            Map.Entry<String, GpuMesh> oldestEntry = iterator.next();
            GpuMesh oldest = oldestEntry.getValue();
            iterator.remove();
            meshLastViewedNanos.remove(oldestEntry.getKey());
            delete(gl, oldest);
            gpuBytes -= oldest.byteCount;
        }
    }

    public void dispose(GL4 gl) {
        for (GpuMesh mesh : meshes.values()) {
            delete(gl, mesh);
        }
        meshes.clear();
        meshLastViewedNanos.clear();
        gpuBytes = 0L;
        seamStitcher.shutdown();
        if (boundsProgram != 0) {
            gl.glDeleteProgram(boundsProgram);
            boundsProgram = 0;
            boundsMvpLocation = -1;
        }
        if (textureProgram != 0) { gl.glDeleteProgram(textureProgram); textureProgram = 0; }
        imageryTextureCache.dispose(gl);
        Jogl4RendererConfigurationShaderSelector.dispose(gl);
    }

    public int getResidentMeshCount() {
        return meshes.size();
    }

    public long getGpuBytesAssigned() {
        return gpuBytes;
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

    private record GpuMesh(int vaoId, int positionVboId, int normalVboId, int uvVboId,
                           int indexBufferId, int vertexCount, int indexCount, long byteCount,
                           GpuBounds bounds, TriangleMesh cpuMesh) {
    }

    private record PickableMesh(DrawCommand command, TriangleMesh mesh, Matrix4x4d modelMatrix,
                                double offsetX, double offsetY, double offsetZ,
                                double relativeScale, double heightExaggeration) {
    }

    public record TerrainPickHit(DrawCommand command, WorldPosition worldPosition, double distanceMetres,
                                 double localX, double localY, double ellipsoidalElevationMetres) {
    }

    private record GpuBounds(int vaoId, int vboId, long byteCount) {
    }

    /** Bounds include every tile vertex, independently of its triangle topology. */
    private record Bounds(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ) {
        private static Bounds fromPositions(double[] positions) {
            if (positions.length == 0 || positions.length % 3 != 0) {
                throw new IllegalArgumentException("Expected complete xyz vertex positions");
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (int offset = 0; offset < positions.length; offset += 3) {
                minX = Math.min(minX, positions[offset]);
                minY = Math.min(minY, positions[offset + 1]);
                minZ = Math.min(minZ, positions[offset + 2]);
                maxX = Math.max(maxX, positions[offset]);
                maxY = Math.max(maxY, positions[offset + 1]);
                maxZ = Math.max(maxZ, positions[offset + 2]);
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        private float[] linePositions() {
            float[] corners = {
                (float) minX, (float) minY, (float) minZ,
                (float) maxX, (float) minY, (float) minZ,
                (float) maxX, (float) maxY, (float) minZ,
                (float) minX, (float) maxY, (float) minZ,
                (float) minX, (float) minY, (float) maxZ,
                (float) maxX, (float) minY, (float) maxZ,
                (float) maxX, (float) maxY, (float) maxZ,
                (float) minX, (float) maxY, (float) maxZ
            };
            int[] edges = {0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7};
            float[] lines = new float[edges.length * 3];
            for (int vertex = 0; vertex < edges.length; vertex++) {
                System.arraycopy(corners, edges[vertex] * 3, lines, vertex * 3, 3);
            }
            return lines;
        }
    }
}
