package planetdemviewer.render;

import java.io.File;
import java.io.IOException;
import java.util.List;
import planetdemviewer.config.Configuration;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileTreeDiscoveryService;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.media.RGBAImageUncompressed;

/**
 * Decides what the palette image shows in a frame: which tiles, and which texture for
 * each one. While a tile is not decoded yet, the closest resident ancestor stands in.
 * T is the texture handle of the graphics backend.
 */
public final class PaletteFramePlanner<T> {
    private final TileImageLoader loader;
    private final TileTreeDiscoveryService discoveryService;
    private final QuadtreeDrawPlanner.LodState lodState = new QuadtreeDrawPlanner.LodState();
    private final TileTextureCache<File, T> textures = new TileTextureCache<>(Configuration.MAX_GPU_TEXTURE_MEMORY);

    PaletteFramePlanner(TileImageLoader loader, TileTreeDiscoveryService discoveryService) {
        this.loader = loader;
        this.discoveryService = discoveryService;
    }

    public List<DrawCommand> selectVisible(PyramidalImageInstance instance, Camera camera, double relativeScale,
                                           boolean uniformLod) {
        return uniformLod
            ? QuadtreeDrawPlanner.selectUniformLod(instance, camera, relativeScale)
            : QuadtreeDrawPlanner.select(instance, camera, relativeScale, lodState);
    }

    public List<DrawCommand> selectAll(PyramidalImageInstance instance, double relativeScale) {
        return QuadtreeDrawPlanner.selectAllLeaves(instance, relativeScale);
    }

    /** A displayed tile asks for its descendants to be discovered. */
    public void tileDisplayed(PyramidalImageInstance instance, DrawCommand command) {
        discoveryService.requestVisible(instance.getImage(), command.node().nearestSelfOrAncestorWithTile());
    }

    /**
     * Interactive frames decode in the background; the offline snapshot waits for each
     * tile instead. Returns null when neither the tile nor any ancestor has a texture.
     */
    public PaletteTile<T> resolve(DrawCommand command, boolean synchronous, TextureUploader<T> gpu) {
        QuadtreeNode node = command.node();
        T texture = null;
        QuadtreeNode textureOwner = null;
        if (node.getTileFile() != null) {
            // Keep the source DEM page recent too when its derived texture is displayed.
            loader.peekElevation(node.getTileFile());
            texture = textures.acquire(node.getTileFile(),
                synchronous ? this::decodeNow : this::decodedInBackground, gpu);
            textureOwner = node;
        }
        if (texture == null) {
            for (QuadtreeNode ancestor = node.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                if (ancestor.getTileFile() == null) {
                    continue;
                }
                texture = textures.peekResident(ancestor.getTileFile());
                if (texture != null) {
                    textureOwner = ancestor;
                    // The fallback ancestor is the page actually displayed.
                    // Refresh its source DEM page as well as its GPU texture.
                    loader.peekElevation(ancestor.getTileFile());
                    break;
                }
            }
        }
        if (texture == null) {
            return null;
        }
        return new PaletteTile<>(texture, textureOwner == node
            ? new double[] {0.0, 0.0, 1.0, 1.0}
            : node.subRectRelativeTo(textureOwner));
    }

    private RGBAImageUncompressed decodeNow(File tileFile) {
        try {
            return loader.loadSynchronously(tileFile);
        }
        catch (IOException ex) {
            return null;
        }
    }

    private RGBAImageUncompressed decodedInBackground(File tileFile) {
        RGBAImageUncompressed decoded = loader.takeReady(tileFile);
        if (decoded == null) {
            loader.requestLoad(tileFile);
        }
        return decoded;
    }

    public void clearTextures(TextureUploader<T> gpu) {
        textures.clear(gpu);
    }

    GpuResourceUsage textureUsage() {
        return textures.usage();
    }
}
