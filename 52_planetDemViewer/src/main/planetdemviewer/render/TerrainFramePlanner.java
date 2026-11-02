package planetdemviewer.render;

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
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.terrain.AdaptiveTerrainMesh;
import planetdemviewer.terrain.ProjectedDeltaCriterion;
import planetdemviewer.terrain.TerrainMeshPlacement;
import planetdemviewer.terrain.TerrainMeshPlan;
import planetdemviewer.terrain.TerrainSeamStitcher;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;
import vsdk.toolkit.media.RGBAImageUncompressed;

/**
 * Decides what the terrain shows in a frame: which tiles, with which seam variant, where,
 * and with which imagery. Mesh variants are keyed by their complete seam neighbourhood:
 * a border stitched against a coarse neighbour must never replace a mesh generated for the
 * same tile under a different LOD neighbourhood. T is the texture handle of the graphics
 * backend, and M its handle for an uploaded mesh.
 */
public final class TerrainFramePlanner<T, M> {
    private static final double Z_OFFSET_TO_WORLD = 1e-4;

    private final TileImageLoader loader;
    private final TileTreeDiscoveryService discoveryService;
    private final ImageryTileLoader imageryLoader;
    private final TerrainSimplificationStatistics simplificationStatistics;
    private final TerrainSeamStitcher seamStitcher = new TerrainSeamStitcher();
    private final QuadtreeDrawPlanner.LodState lodState = new QuadtreeDrawPlanner.LodState();
    private final TileTextureCache<TileAddress, T> imageryTextures = new TileTextureCache<>(Long.MAX_VALUE);
    private final GpuResourceCache<String, M> meshes =
        new GpuResourceCache<>(Configuration.MAX_GPU_TEXTURE_MEMORY, 1);

    TerrainFramePlanner(TileImageLoader loader, TileTreeDiscoveryService discoveryService,
                        ImageryTileLoader imageryLoader, TerrainSimplificationStatistics simplificationStatistics) {
        this.simplificationStatistics = simplificationStatistics;
        this.loader = loader;
        this.discoveryService = discoveryService;
        this.imageryLoader = imageryLoader;
    }

    /** Starts generating the meshes that are not resident. */
    public List<TerrainDrawItem> plan(PyramidalImageInstance instance, TerrainFrameSettings settings) {
        List<DrawCommand> commands = settings.uniformLod()
            ? QuadtreeDrawPlanner.selectUniformLod(instance, settings.cullingCamera(), settings.relativeScale())
            : QuadtreeDrawPlanner.selectTerrain(instance, settings.cullingCamera(), settings.relativeScale(),
                settings.heightExaggeration(), loader, lodState);
        for (DrawCommand command : commands) {
            discoveryService.requestVisible(instance.getImage(), command.node().nearestSelfOrAncestorWithTile());
        }

        List<TerrainMeshPlan> plans = seamStitcher.plan(commands, settings.subdivisionScheme());
        Map<String, CompletableFuture<TriangleMesh>> preparations = new LinkedHashMap<>();
        for (TerrainMeshPlan plan : plans) {
            if (!meshes.contains(plan.variantKey())) {
                CompletableFuture<TriangleMesh> preparation = seamStitcher.prepare(plan, loader);
                if (preparation != null) {
                    preparations.put(plan.variantKey(), preparation);
                }
            }
        }

        double offsetZ = instance.getZOffset() * Z_OFFSET_TO_WORLD;
        Matrix4x4d modelMatrix = new Matrix4x4d()
            .translation(instance.getOffsetX(), instance.getOffsetY(), offsetZ)
            .multiply(new Matrix4x4d().scale(
                settings.relativeScale(),
                settings.relativeScale(),
                settings.relativeScale() * settings.heightExaggeration()));
        List<TerrainDrawItem> items = new ArrayList<>(plans.size());
        for (TerrainMeshPlan plan : plans) {
            // A resident GPU mesh still represents a visible DEM page. Renew the
            // source page's LRU timestamp even when no mesh preparation is needed.
            loader.peekElevation(plan.command().node().getTileFile());
            items.add(new TerrainDrawItem(plan.command(), plan.variantKey(), preparations.get(plan.variantKey()),
                modelMatrix, instance.getOffsetX(), instance.getOffsetY(), offsetZ,
                settings.relativeScale(), settings.heightExaggeration(),
                settings.imageryVisible() && imageryLoader != null,
                boundsColor(plan.command(), settings)));
        }
        return items;
    }

    /** The selected tile's bounds are always shown, and they stand out from the rest. */
    private static float[] boundsColor(DrawCommand command, TerrainFrameSettings settings) {
        boolean selected = settings.selectedTile() != null
            && settings.selectedTile().quadkey().equals(command.node().getId());
        if (!settings.tileBoundsVisible() && !selected) {
            return null;
        }
        return new float[] {1.0f, 1.0f, selected ? 0.0f : 1.0f};
    }

    /**
     * Updates the triangles of a view-dependent mesh, always for the culling camera, as the
     * tile selection does. Other views show that same mesh from outside, and only the
     * culling camera's own view counts in the statistics, so each frame counts once.
     *
     * @return the triangles the backend must upload, or null when it already has them
     */
    public int[] updateTriangles(TerrainDrawItem item, TriangleMesh mesh, TerrainFrameSettings settings,
                                 Camera renderingCamera) {
        if (!(mesh instanceof AdaptiveTerrainMesh adaptive)) {
            return null;
        }
        ProjectedDeltaCriterion criterion = ProjectedDeltaCriterion.of(
            settings.cullingCamera(), settings.screenErrorThresholdPixels());
        if (criterion == null) {
            adaptive.useAllTriangles();
        }
        else {
            adaptive.simplify(criterion, new TerrainMeshPlacement(item.offsetX(), item.offsetY(), item.offsetZ(),
                item.relativeScale(), item.heightExaggeration()));
            if (renderingCamera == settings.cullingCamera()) {
                simplificationStatistics.blockDrawn(adaptive);
            }
        }
        return adaptive.takeTrianglesToUpload();
    }

    /** Counts as a use of the mesh, which renews it in the LRU. */
    public M residentMesh(String variantKey) {
        return meshes.get(variantKey);
    }

    /** Returns the meshes evicted to stay within the budget, which the backend must destroy. */
    public List<M> meshUploaded(String variantKey, M mesh, long bytes) {
        return meshes.put(variantKey, mesh, bytes);
    }

    /** Returns every mesh, which the backend must destroy. */
    public List<M> clearMeshes() {
        return meshes.clear();
    }

    GpuResourceUsage meshUsage() {
        return meshes;
    }

    public void releasePreparedMesh(String variantKey) {
        seamStitcher.releasePreparedMesh(variantKey);
    }

    /** Returns null while the item shows no imagery, or its page is not decoded yet. */
    public T acquireImagery(TerrainDrawItem item, TextureUploader<T> gpu) {
        if (!item.imageryVisible()) {
            return null;
        }
        return imageryTextures.acquire(TileAddress.fabdem(item.command().node().getId()),
            this::decodedImagery, gpu);
    }

    private RGBAImageUncompressed decodedImagery(TileAddress address) {
        RGBAImageUncompressed image = imageryLoader.takeReady(address);
        if (image == null) {
            imageryLoader.request(address);
        }
        return image;
    }

    /** Frees the textures of a tile and its descendants. */
    public void releaseImagerySubtree(TileAddress subtree, TextureUploader<T> gpu) {
        imageryTextures.release(subtree::contains, gpu);
    }

    public void clearImagery(TextureUploader<T> gpu) {
        imageryTextures.clear(gpu);
    }

    public void shutdown() {
        seamStitcher.shutdown();
    }
}
