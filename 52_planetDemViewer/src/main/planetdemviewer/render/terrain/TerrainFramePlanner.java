package planetdemviewer.render.terrain;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import planetdemviewer.config.Configuration;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileTreeDiscoveryService;
import planetdemviewer.io.imagery.ImageryBlockLink;
import planetdemviewer.io.imagery.ImageryTileLoader;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import planetdemviewer.render.DirectBuffers;
import planetdemviewer.render.GpuResourceCache;
import planetdemviewer.render.GpuResourceUsage;
import planetdemviewer.render.TextureUploader;
import planetdemviewer.render.TileTextureCache;
import planetdemviewer.spatial.TileAddress;
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
 *
 * <p>References:</p>
 * <ul>
 * <li>[LERB2010] Lerbour, R., Marvie, J.-E. and Gautron, P. "Adaptive Real-Time Rendering of
 * Planetary Terrains", WSCG 2010.</li>
 * </ul>
 */
public final class TerrainFramePlanner<T, M> {
    private static final double Z_OFFSET_TO_WORLD = 1e-4;

    private final TileImageLoader loader;
    private final TileTreeDiscoveryService discoveryService;
    private final ImageryTileLoader imageryLoader;
    private final TerrainSimplificationStatistics simplificationStatistics;
    private final TerrainSeamStitcher seamStitcher = new TerrainSeamStitcher();
    private final QuadtreeDrawPlanner.LodState lodState = new QuadtreeDrawPlanner.LodState();
    private final LinkedImageryBlocks linkedImagery = new LinkedImageryBlocks();
    private final TileTextureCache<TileAddress, T> imageryTextures =
        new TileTextureCache<>(Configuration.MAX_GPU_TEXTURE_MEMORY);
    private final GpuResourceCache<String, ResidentMesh<M>> meshes =
        new GpuResourceCache<>(Configuration.MAX_GPU_TEXTURE_MEMORY, 1);
    private final TerrainPickFrame pickFrame;
    private final TerrainImageryStatistics imageryStatistics;
    private IntBuffer indexScratch;

    public TerrainFramePlanner(TileImageLoader loader, TileTreeDiscoveryService discoveryService,
                               ImageryTileLoader imageryLoader, TerrainSimplificationStatistics simplificationStatistics,
                               TerrainImageryStatistics imageryStatistics, TerrainPickFrame pickFrame) {
        this.imageryStatistics = imageryStatistics;
        this.simplificationStatistics = simplificationStatistics;
        this.loader = loader;
        this.discoveryService = discoveryService;
        this.imageryLoader = imageryLoader;
        this.pickFrame = pickFrame;
    }

    /**
     * Leaves on the GPU everything the terrain needs to draw an instance from a camera, and
     * returns what to draw, which also becomes what picking sees.
     */
    public List<TerrainMeshDraw<T, M>> prepareFrame(PyramidalImageInstance instance, TerrainFrameSettings settings,
                                                    Camera renderingCamera, TerrainMeshUploader<M> meshGpu,
                                                    TextureUploader<T> textureGpu) {
        return prepareItems(plan(instance, settings), settings, renderingCamera, meshGpu, textureGpu);
    }

    List<TerrainMeshDraw<T, M>> prepareItems(List<TerrainDrawItem> items, TerrainFrameSettings settings,
                                             Camera renderingCamera, TerrainMeshUploader<M> meshGpu,
                                             TextureUploader<T> textureGpu) {
        for (TerrainDrawItem item : items) {
            if (meshes.get(item.variantKey()) == null && item.preparation() != null) {
                try {
                    upload(item, meshGpu);
                }
                catch (RuntimeException ignored) {
                    // Keep rendering other tiles; a later frame may retry after cache eviction.
                }
                finally {
                    seamStitcher.releasePreparedMesh(item.variantKey());
                }
            }
        }
        // Looked up after every upload, since an upload may evict a mesh of this same frame.
        List<TerrainDrawItem> drawnItems = new ArrayList<>(items.size());
        List<ResidentMesh<M>> residentMeshes = new ArrayList<>(items.size());
        List<TriangleMesh> cpuMeshes = new ArrayList<>(items.size());
        for (TerrainDrawItem item : items) {
            ResidentMesh<M> mesh = meshes.get(item.variantKey());
            if (mesh != null) {
                drawnItems.add(item);
                residentMeshes.add(mesh);
                cpuMeshes.add(mesh.cpuMesh());
            }
        }
        List<List<ImageryBlock>> imageryBlocks = new ArrayList<>(drawnItems.size());
        for (int i = 0; i < drawnItems.size(); i++) {
            List<ImageryBlock> blocks = imageryBlocks(drawnItems.get(i), settings);
            imageryBlocks.add(blocks);
            if (cpuMeshes.get(i) instanceof QuadrantTerrainMesh quadrantMesh) {
                quadrantMesh.useQuadrants(blocks.stream().map(ImageryBlock::quadrant).toList());
            }
        }
        updateTriangles(drawnItems, cpuMeshes, settings, renderingCamera);
        Matrix4x4d projection = renderingCamera.calculateProjectionMatrix();
        List<TerrainMeshDraw<T, M>> draws = new ArrayList<>(drawnItems.size());
        for (int i = 0; i < drawnItems.size(); i++) {
            TerrainDrawItem item = drawnItems.get(i);
            ResidentMesh<M> mesh = residentMeshes.get(i);
            int[] changedTriangles = trianglesToUpload(mesh.cpuMesh());
            if (changedTriangles != null) {
                indexScratch = DirectBuffers.ints(changedTriangles, indexScratch);
                meshGpu.replaceTriangles(mesh.gpuMesh(), indexScratch);
            }
            List<TerrainTextureBlock<T>> textureBlocks =
                textureBlocks(item, mesh.cpuMesh(), imageryBlocks.get(i), settings, renderingCamera, textureGpu);
            pickFrame.meshDrawn(item, mesh.cpuMesh(), textureBlocks);
            draws.add(new TerrainMeshDraw<>(item, mesh.gpuMesh(), textureBlocks,
                projection.multiply(item.modelMatrix()), item.modelMatrix().invert().transpose()));
        }
        return draws;
    }

    private void upload(TerrainDrawItem item, TerrainMeshUploader<M> gpu) {
        TriangleMesh generated = item.preparation().join();
        TerrainMeshBuffers source = TerrainMeshBuffers.of(generated, item.command().node());
        M uploaded = gpu.upload(source);
        for (M evicted : meshUploaded(item.variantKey(), uploaded, generated, source.byteSize())) {
            gpu.destroy(evicted);
        }
    }

    /** Starts generating the meshes that are not resident. */
    private List<TerrainDrawItem> plan(PyramidalImageInstance instance, TerrainFrameSettings settings) {
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
     * Updates the triangles of the view-dependent meshes of a frame, always for the culling
     * camera, as the tile selection does. Other views show those same meshes from outside, and
     * only the culling camera's own view counts in the statistics, so each frame counts once.
     * All the blocks go first, since a block can change the shared vertices of the others.
     *
     * @param items the items that will be drawn
     * @param meshes the mesh of each item
     */
    private void updateTriangles(List<TerrainDrawItem> items, List<TriangleMesh> meshes,
                                 TerrainFrameSettings settings, Camera renderingCamera) {
        ProjectedDeltaCriterion criterion = ProjectedDeltaCriterion.of(
            settings.cullingCamera(), settings.screenErrorThresholdPixels());
        List<BlockBoundaryConstraints.Block> blocks = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (meshes.get(i) instanceof AdaptiveTerrainMesh adaptive) {
                if (criterion == null) {
                    adaptive.useAllTriangles();
                }
                else {
                    blocks.add(new BlockBoundaryConstraints.Block(items.get(i).command().node(), adaptive));
                }
            }
        }
        BlockBoundaryConstraints.apply(blocks, settings.dependenciesCrossBlocks());
        for (int i = 0; i < items.size(); i++) {
            TerrainDrawItem item = items.get(i);
            if (criterion != null && meshes.get(i) instanceof AdaptiveTerrainMesh adaptive) {
                adaptive.updateVertexSelection(criterion, new TerrainMeshPlacement(item.offsetX(), item.offsetY(),
                    item.offsetZ(), item.relativeScale(), item.heightExaggeration()));
            }
        }
        for (TriangleMesh mesh : meshes) {
            if (mesh instanceof QuadrantTerrainMesh quadrantMesh && !(mesh instanceof AdaptiveTerrainMesh)) {
                quadrantMesh.applyQuadrants();
            }
        }
        for (BlockBoundaryConstraints.Block block : blocks) {
            block.mesh().selectTriangles();
            if (renderingCamera == settings.cullingCamera()) {
                simplificationStatistics.blockDrawn(block.mesh());
            }
        }
    }

    /** Returns the triangles the backend must upload, or null when it already has them. */
    private int[] trianglesToUpload(TriangleMesh mesh) {
        return mesh instanceof QuadrantTerrainMesh quadrantMesh ? quadrantMesh.takeTrianglesToUpload() : null;
    }

    /** Returns the meshes evicted to stay within the budget. */
    public List<M> meshUploaded(String variantKey, M gpuMesh, TriangleMesh cpuMesh, long bytes) {
        return meshes.put(variantKey, new ResidentMesh<>(gpuMesh, cpuMesh), bytes).stream()
            .map(ResidentMesh::gpuMesh).toList();
    }

    /** Destroys every mesh. */
    public void clearMeshes(TerrainMeshUploader<M> gpu) {
        meshes.clear().forEach(mesh -> gpu.destroy(mesh.gpuMesh()));
    }

    public GpuResourceUsage meshUsage() {
        return meshes;
    }

    /** Which image each part of the tile wants; with same-level imagery, the tile is one part. */
    private List<ImageryBlock> imageryBlocks(TerrainDrawItem item, TerrainFrameSettings settings) {
        QuadtreeNode node = item.command().node();
        if (!item.imageryVisible()) {
            return List.of(new ImageryBlock(GridQuadrant.WHOLE_TILE, null));
        }
        if (!settings.independentImageryLevels()) {
            return List.of(new ImageryBlock(GridQuadrant.WHOLE_TILE, TileAddress.fabdem(node.getId())));
        }
        DemTile elevations = loader.peekElevation(node.getTileFile());
        double minMetres = elevations == null ? Configuration.MINIMUM_ELEVATION_METRES
            : elevations.canonicalCoreElevationRange().minimumMetres();
        double maxMetres = elevations == null ? Configuration.MAXIMUM_ELEVATION_METRES
            : elevations.canonicalCoreElevationRange().maximumMetres();
        return linkedImagery.select(node, imageryLoader, settings.cullingCamera(),
            new TerrainMeshPlacement(item.offsetX(), item.offsetY(), item.offsetZ(), item.relativeScale(),
                item.heightExaggeration()),
            minMetres / Configuration.WORLD_WIDTH_METRES, maxMetres / Configuration.WORLD_WIDTH_METRES);
    }

    private List<TerrainTextureBlock<T>> textureBlocks(TerrainDrawItem item, TriangleMesh mesh,
                                                       List<ImageryBlock> blocks, TerrainFrameSettings settings,
                                                       Camera renderingCamera, TextureUploader<T> gpu) {
        TileAddress tile = TileAddress.fabdem(item.command().node().getId());
        List<TerrainTextureBlock<T>> result = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            int first = mesh instanceof QuadrantTerrainMesh quadrantMesh ? quadrantMesh.firstIndex(i) : 0;
            int count = mesh instanceof QuadrantTerrainMesh quadrantMesh ? quadrantMesh.indexCount(i)
                : mesh.getTriangleIndexes().length;
            TileAddress wanted = blocks.get(i).address();
            T texture = null;
            TileAddress source = null;
            if (wanted != null && !settings.independentImageryLevels()) {
                texture = imageryTextures.acquire(wanted, this::decodedImagery, gpu);
                source = wanted;
                result.add(TerrainTextureBlock.unmapped(texture, texture == null ? null : wanted, first, count));
            }
            else {
                if (wanted != null) {
                    source = LinkedImageryBlocks.nearestLinkedImage(wanted, imageryLoader);
                    if (source != null) {
                        texture = imageryTextures.acquire(source, this::decodedImagery, gpu);
                    }
                    // Section [LERB2010].3.3: an available level is used while the desired one loads.
                    while (texture == null && source != null) {
                        source = source.parent();
                        texture = source == null ? null : imageryTextures.peekResident(source);
                    }
                }
                result.add(texture == null ? TerrainTextureBlock.unmapped(null, null, first, count)
                    : TerrainTextureBlock.mapped(texture, tile, source, first, count));
            }
            if (renderingCamera == settings.cullingCamera()) {
                imageryStatistics.blockDrawn(tile.level(), texture == null ? -1 : source.level());
            }
        }
        return result;
    }

    private RGBAImageUncompressed decodedImagery(TileAddress address) {
        RGBAImageUncompressed image = imageryLoader.takeReady(address);
        if (image == null) {
            // An unknown link is resolved in the background, and a later frame asks again.
            ImageryBlockLink link = imageryLoader.link(address);
            if (link != null && link.hasImage()) {
                imageryLoader.request(address);
            }
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

    /** The CPU mesh stays available for picking and for view-dependent simplification. */
    private record ResidentMesh<M>(M gpuMesh, TriangleMesh cpuMesh) {
    }
}
