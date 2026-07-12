package pyramidalimageexporter.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import pyramidalimageexporter.common.statistics.PyramidalImageAdditionStatistics;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.PyramidalImageExporterModel;
import pyramidalimageexporter.model.TileCoord;
import pyramidalimageexporter.processing.uncles.RootPathResolver;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;

/**
 * Writes the reconstructed pyramid to disk as a quadtree of PNG files: the
 * root tile is "0.png" in the destination directory, and every deeper tile
 * "0xy..." lives in a folder named after its own full quadkey, nested one
 * folder per ancestor, e.g. 00/000/0000.png. Any tile from any matrix layer
 * is eligible as long as {@link RootPathResolver} can anchor it to a full
 * path from the root, either directly (its own id already is a quadkey) or
 * through a chain of "uncle" relationships to an already-anchored tile.
 *
 * The export is additive-destructive: an existing destination directory is
 * reused as-is (no extra validation of its prior contents), and each tile
 * is compared against whatever image already occupies its slot, so a
 * pre-existing identical image is left untouched, a pre-existing different
 * image is overwritten, and a new slot is simply written.
 */
public final class PyramidalImageExporter {
    private static final int TILE_PIXEL_SIZE = 256;
    private static final int PROGRESS_REPORT_INTERVAL = 100;

    private final Map<String, RGBImageUncompressed> sourceImageCache = new HashMap<>();
    private final RootPathResolver rootPathResolver = new RootPathResolver();

    public void export(PyramidalImageExporterModel model) {
        if (model == null) {
            return;
        }
        String destination = model.getSessionPyramidalImageExportPath();
        if (destination == null) {
            reportStatus(model, "Export failed: no sessionPyramidalImageExportPath was provided.");
            return;
        }
        Path rootDirectory = Path.of(destination);
        if (!ensureDirectory(rootDirectory)) {
            reportStatus(model, "Export failed: destination directory is not accessible: " + rootDirectory);
            return;
        }

        RootPathResolver.Resolution resolution = rootPathResolver.resolve(model.getMatrixLayers());
        if (!resolution.discardedIds().isEmpty()) {
            System.out.println(
                "PyramidalImageExporter: " + resolution.discardedIds().size()
                    + " tile(s) discarded due to ambiguous uncle relationships (inconsistent root paths)."
            );
        }

        sourceImageCache.clear();
        int totalTiles = countExportableTiles(model, resolution);
        System.out.println("PyramidalImageExporter: export starting, " + totalTiles + " tiles to write to " + rootDirectory);
        PyramidalImageAdditionStatistics statistics = new PyramidalImageAdditionStatistics();
        int failed = 0;
        int processed = 0;
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (TileCoord tile : layer.getTiles()) {
                String fullPath = tile == null ? null : resolution.pathById().get(tile.getId());
                if (fullPath == null) {
                    continue;
                }
                if (!exportTile(rootDirectory, fullPath, tile, statistics)) {
                    failed++;
                }
                processed++;
                if (processed % PROGRESS_REPORT_INTERVAL == 0) {
                    System.out.println(
                        "PyramidalImageExporter: processed " + processed + "/" + totalTiles + " tiles..."
                    );
                }
            }
        }
        sourceImageCache.clear();

        System.out.println("PyramidalImageExporter: " + statistics);
        reportStatus(
            model,
            "Export complete: " + processed + " tiles processed to " + rootDirectory
                + (failed > 0 ? " (" + failed + " failed)" : "")
        );
    }

    private static int countExportableTiles(PyramidalImageExporterModel model, RootPathResolver.Resolution resolution) {
        int total = 0;
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (TileCoord tile : layer.getTiles()) {
                if (tile != null && resolution.pathById().get(tile.getId()) != null) {
                    total++;
                }
            }
        }
        return total;
    }

    private static void reportStatus(PyramidalImageExporterModel model, String message) {
        model.setLastExportStatus(message);
        System.out.println("PyramidalImageExporter: " + message);
    }

    private boolean exportTile(
        Path rootDirectory,
        String fullPath,
        TileCoord tile,
        PyramidalImageAdditionStatistics statistics
    ) {
        RGBImageUncompressed sourceImage = loadSourceImage(tile.getTextureFile());
        if (sourceImage == null) {
            return false;
        }
        Path tileDirectory = directoryFor(rootDirectory, fullPath);
        if (!ensureDirectory(tileDirectory)) {
            return false;
        }
        RGBImageUncompressed tileImage = cropTile(sourceImage, tile);
        File outputFile = tileDirectory.resolve(fullPath + ".png").toFile();

        if (outputFile.isFile()) {
            RGBImageUncompressed existingImage;
            try {
                existingImage = ImagePersistence.importRGB(outputFile);
            }
            catch (Exception ex) {
                System.out.println("PyramidalImageExporter: could not read existing " + outputFile + ": " + ex.getMessage());
                return false;
            }
            if (imagesAreEqual(existingImage, tileImage)) {
                statistics.incrementIgnoredExistingImages();
                return true;
            }
            if (!writeImage(outputFile, tileImage)) {
                return false;
            }
            statistics.incrementUpdatedImages();
            return true;
        }

        if (!writeImage(outputFile, tileImage)) {
            return false;
        }
        statistics.incrementNewImages();
        return true;
    }

    private static boolean writeImage(File outputFile, RGBImageUncompressed image) {
        try {
            ImagePersistence.exportPNG(outputFile, image);
        }
        catch (Exception ex) {
            System.out.println("PyramidalImageExporter: could not write " + outputFile + ": " + ex.getMessage());
            return false;
        }
        return true;
    }

    private static boolean imagesAreEqual(RGBImageUncompressed a, RGBImageUncompressed b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getXSize() != b.getXSize() || a.getYSize() != b.getYSize()) {
            return false;
        }
        return a.getRawImageDirectBuffer().equals(b.getRawImageDirectBuffer());
    }

    /**
     * The root tile "0" is written directly in rootDirectory; any deeper
     * tile "0xy..." lives in a folder named after its own full quadkey,
     * nested under a folder per each ancestor from the second digit on
     * (e.g. quadkey "0021" resolves to rootDirectory/00/002/0021/0021.png).
     */
    private static Path directoryFor(Path rootDirectory, String fullId) {
        Path directory = rootDirectory;
        for (int length = 2; length <= fullId.length(); length++) {
            directory = directory.resolve(fullId.substring(0, length));
        }
        return directory;
    }

    private RGBImageUncompressed loadSourceImage(String textureFile) {
        if (textureFile == null || textureFile.isBlank()) {
            return null;
        }
        RGBImageUncompressed cached = sourceImageCache.get(textureFile);
        if (cached != null) {
            return cached;
        }
        Path sourcePath = Path.of(textureFile);
        if (!Files.isRegularFile(sourcePath) || !Files.isReadable(sourcePath)) {
            return null;
        }
        try {
            RGBImageUncompressed loaded = ImagePersistence.importRGB(sourcePath.toFile());
            sourceImageCache.put(textureFile, loaded);
            return loaded;
        }
        catch (Exception ex) {
            System.out.println("PyramidalImageExporter: could not read " + sourcePath + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Samples the tile's texture sub-rectangle (OpenGL convention: v = 0 at
     * the image bottom, so it is flipped to the source image's top-down
     * raster rows) with nearest-neighbor sampling, matching the GL_NEAREST
     * filtering the interactive renderer uses for the same tiles.
     */
    private static RGBImageUncompressed cropTile(RGBImageUncompressed sourceImage, TileCoord tile) {
        RGBImageUncompressed cropped = new RGBImageUncompressed();
        cropped.init(TILE_PIXEL_SIZE, TILE_PIXEL_SIZE);
        double v1 = tile.getTexV1();
        double v0 = tile.getTexV0();
        double u0 = tile.getTexU0();
        double u1 = tile.getTexU1();
        for (int y = 0; y < TILE_PIXEL_SIZE; y++) {
            double glV = v1 - (v1 - v0) * ((y + 0.5) / TILE_PIXEL_SIZE);
            double rasterV = 1.0 - glV;
            for (int x = 0; x < TILE_PIXEL_SIZE; x++) {
                double u = u0 + (u1 - u0) * ((x + 0.5) / TILE_PIXEL_SIZE);
                ColorRgb color = sourceImage.getColorRgbNearest(u, rasterV);
                cropped.putPixel(x, y, toByte(color.getR()), toByte(color.getG()), toByte(color.getB()));
            }
        }
        return cropped;
    }

    private static byte toByte(double channel) {
        int value = (int) Math.round(Math.max(0.0, Math.min(1.0, channel)) * 255.0);
        return (byte) value;
    }

    private static boolean ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        }
        catch (IOException ex) {
            System.out.println("PyramidalImageExporter: could not create directory " + directory + ": " + ex.getMessage());
            return false;
        }
        return Files.isDirectory(directory) && Files.isWritable(directory);
    }
}
