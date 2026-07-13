package pyramidalimageexporter.processing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import pyramidalimageexporter.config.Configuration;
import pyramidalimageexporter.io.MatrixLayerIdRewriteWriter;
import pyramidalimageexporter.model.PyramidalImageWriteStatistics;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.model.state.PyramidalImageExporterState;
import pyramidalimageexporter.processing.content.ContentHashRootPathResolver;
import pyramidalimageexporter.processing.uncles.ExternalUncleBridgeBuilder;
import pyramidalimageexporter.processing.uncles.FrameJsonUncleMetadataRestorer;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;

/**
 * Writes this session's reconstructed pyramid to disk as a quadtree of PNG
 * files inside the session's own input folder: the root tile is "0.png" in
 * the destination directory, and every deeper tile "0xy..." lives in a
 * folder named after its own full quadkey, nested one folder per ancestor,
 * e.g. 00/000/0000.png. Any tile from any matrix layer is eligible as long
 * as {@link TileRootPathResolver} can anchor it to a full path from the root,
 * either directly (its own id already is a quadkey) or through a chain of
 * "uncle" relationships to an already-anchored tile.
 *
 * Before that resolution runs, {@link ContentHashRootPathResolver} gives any
 * tile whose id isn't already a quadkey one more chance: if its texture file
 * is a byte-for-byte duplicate of an already-catalogued top-level image, its
 * id is rewritten in place to that quadkey, both in memory and in its source
 * matrix_&lt;n&gt;/matrixLayer.json, so the tile (and every future run
 * reading that file) becomes directly anchored with no uncle chain needed.
 *
 * The export is strictly session-local: it never reads tiles from any
 * existing pyramidal image (its own destination included) and each slot is
 * simply (re)written from this session's data. Merging different capture
 * sessions' pyramidal images is the responsibility of a separate program.
 */
public final class SessionPyramidalImageExportService {
    private static final int TILE_PIXEL_SIZE = 256;
    private static final int PROGRESS_REPORT_INTERVAL = 100;

    private final Map<String, RGBImageUncompressed> sourceImageCache = new HashMap<>();
    private final TileRootPathResolver rootPathResolver = new TileRootPathResolver();

    public void export(PyramidalImageExporterState model) {
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

        applyContentHashAnchors(model);

        Path outputDirectory = Path.of(Configuration.outputDirectory()).toAbsolutePath().normalize();
        new FrameJsonUncleMetadataRestorer().enrich(model.getMatrixLayers(), outputDirectory);

        Map<String, String> externalFullPaths =
            buildExternalUncleFullPaths(model.getCataloguedQuadPathsByImagePath());
        ExternalUncleBridgeBuilder.Bridge bridge = new ExternalUncleBridgeBuilder().build(
            model.getMatrixLayers(),
            model.getCataloguedQuadPathsByImagePath(),
            outputDirectory
        );
        externalFullPaths.putAll(bridge.fullPathByExternalId());
        System.out.println(
            "SessionPyramidalImageExportService: " + externalFullPaths.size()
                + " externally anchored id(s) and " + bridge.aliasById().size()
                + " dangling-uncle alias(es) available for root path resolution."
        );
        TileRootPathResolver.Resolution resolution =
            rootPathResolver.resolve(model.getMatrixLayers(), externalFullPaths, bridge.aliasById());
        reportPlacement(model, resolution);

        sourceImageCache.clear();
        int totalTiles = countExportableTiles(model, resolution);
        System.out.println("SessionPyramidalImageExportService: export starting, " + totalTiles + " tiles to write to " + rootDirectory);
        PyramidalImageWriteStatistics statistics = new PyramidalImageWriteStatistics();
        int failed = 0;
        int processed = 0;
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
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
                        "SessionPyramidalImageExportService: processed " + processed + "/" + totalTiles + " tiles..."
                    );
                }
            }
        }
        sourceImageCache.clear();

        System.out.println("SessionPyramidalImageExportService: " + statistics);
        reportStatus(
            model,
            "Export complete: " + processed + " tiles processed to " + rootDirectory
                + (failed > 0 ? " (" + failed + " failed)" : "")
        );
    }

    /**
     * Placement audit printed before writing: per layer, how many tiles got
     * an absolute root path (and at which pyramid level(s)), how many were
     * left out and why is visible instead of silently skipped — a layer with
     * zero placed tiles is a whole group of the traced session that will NOT
     * appear in the destination pyramid.
     */
    private static void reportPlacement(PyramidalImageExporterState model, TileRootPathResolver.Resolution resolution) {
        System.out.println("SessionPyramidalImageExportService: placement report:");
        List<String> unplacedLayers = new ArrayList<>();
        Map<Integer, Integer> placedTilesByLevel = new TreeMap<>();
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null || layer.getTiles().isEmpty()) {
                continue;
            }
            int total = 0;
            int placed = 0;
            int ambiguous = 0;
            Set<Integer> levels = new TreeSet<>();
            List<String> unplacedSample = new ArrayList<>();
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null || tile.getId().isBlank()) {
                    continue;
                }
                total++;
                String fullPath = resolution.pathById().get(tile.getId());
                if (fullPath != null) {
                    placed++;
                    int level = fullPath.length() - 1;
                    levels.add(level);
                    placedTilesByLevel.merge(level, 1, Integer::sum);
                }
                else {
                    if (resolution.discardedIds().contains(tile.getId())) {
                        ambiguous++;
                    }
                    if (unplacedSample.size() < 3) {
                        unplacedSample.add(tile.getId());
                    }
                }
            }
            StringBuilder line = new StringBuilder("  ")
                .append(layer.getSourceFolderName())
                .append(": ").append(placed).append("/").append(total).append(" tiles placed");
            if (!levels.isEmpty()) {
                line.append(" at level(s) ").append(levels);
            }
            if (placed < total) {
                line.append(" | ").append(total - placed).append(" NOT placed");
                if (ambiguous > 0) {
                    line.append(" (").append(ambiguous).append(" ambiguous)");
                }
                line.append(", e.g. ").append(unplacedSample);
            }
            System.out.println(line);
            if (placed == 0) {
                unplacedLayers.add(layer.getSourceFolderName() + " (" + total + " tiles)");
            }
        }
        System.out.println("SessionPyramidalImageExportService: placed tiles per pyramid level: " + placedTilesByLevel);
        if (!unplacedLayers.isEmpty()) {
            System.out.println(
                "SessionPyramidalImageExportService: WARNING - " + unplacedLayers.size()
                    + " layer(s) with NO placed tiles will NOT appear in the destination pyramid: "
                    + unplacedLayers
            );
        }
        if (!resolution.discardedIds().isEmpty()) {
            List<String> sample = resolution.discardedIds().stream().sorted().limit(20).toList();
            System.out.println(
                "SessionPyramidalImageExportService: " + resolution.discardedIds().size()
                    + " tile(s) discarded due to ambiguous uncle relationships (inconsistent root paths): " + sample
            );
        }
    }

    private static void applyContentHashAnchors(PyramidalImageExporterState model) {
        String inputFolder = model.getInputFolder();
        if (inputFolder == null) {
            return;
        }
        ContentHashRootPathResolver resolver = new ContentHashRootPathResolver();
        resolver.indexCataloguedImages(model.getCataloguedQuadPathsByImagePath());

        MatrixLayerIdRewriteWriter rewriter = new MatrixLayerIdRewriteWriter();
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null || layer.getSourceFolderName() == null) {
                continue;
            }
            Map<String, String> newIdByOldId = new HashMap<>();
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null || isQuadPath(tile.getId())) {
                    continue;
                }
                Optional<String> resolvedQuadPath = resolver.resolveQuadPath(tile.getTextureFile());
                if (resolvedQuadPath.isEmpty()) {
                    continue;
                }
                newIdByOldId.put(tile.getId(), resolvedQuadPath.get());
                tile.setId(resolvedQuadPath.get());
            }
            if (newIdByOldId.isEmpty()) {
                continue;
            }
            Path matrixLayerJsonFile = Path.of(inputFolder)
                .resolve(layer.getSourceFolderName())
                .resolve("matrixLayer.json");
            rewriter.rewriteIds(matrixLayerJsonFile, newIdByOldId);
            System.out.println(
                "SessionPyramidalImageExportService: anchored " + newIdByOldId.size() + " tile(s) in "
                    + layer.getSourceFolderName() + " by content match, persisted to " + matrixLayerJsonFile
            );
        }
    }

    private static boolean isQuadPath(String id) {
        return id != null && id.matches("[0-3]+");
    }

    /**
     * Catalogued top-level images live at &lt;outputDir&gt;/&lt;frame&gt;/256x256_&lt;n&gt;.png
     * and matrix tiles reference them in their uncles as "&lt;frame&gt;_&lt;n&gt;" (the
     * pre-normalization tile id, e.g. "00012_61"). Rebuilding that id from
     * each catalogued path yields the id-to-full-root-path bridge that lets
     * TileRootPathResolver anchor a new session's tiles to the absolute quadtree.
     */
    private static Map<String, String> buildExternalUncleFullPaths(Map<String, String> quadLabelByImagePath) {
        Map<String, String> out = new HashMap<>();
        if (quadLabelByImagePath == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : quadLabelByImagePath.entrySet()) {
            Path imagePath = Path.of(entry.getKey());
            String fileName = imagePath.getFileName().toString();
            Path frameDirectory = imagePath.getParent();
            if (frameDirectory == null || frameDirectory.getFileName() == null
                || !fileName.startsWith("256x256_") || !fileName.endsWith(".png")) {
                continue;
            }
            String tileNumber = fileName.substring("256x256_".length(), fileName.length() - ".png".length());
            String frameToken = frameDirectory.getFileName().toString();
            String label = entry.getValue();
            String fullPath = "0".equals(label) ? "0" : "0" + label;
            out.put(frameToken + "_" + tileNumber, fullPath);
            String unpaddedFrameToken = frameToken.replaceFirst("^0+(?=.)", "");
            out.putIfAbsent(unpaddedFrameToken + "_" + tileNumber, fullPath);
        }
        return out;
    }

    private static int countExportableTiles(PyramidalImageExporterState model, TileRootPathResolver.Resolution resolution) {
        int total = 0;
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile != null && resolution.pathById().get(tile.getId()) != null) {
                    total++;
                }
            }
        }
        return total;
    }

    private static void reportStatus(PyramidalImageExporterState model, String message) {
        model.setLastExportStatus(message);
        System.out.println("SessionPyramidalImageExportService: " + message);
    }

    private boolean exportTile(
        Path rootDirectory,
        String fullPath,
        MatrixLayerTile tile,
        PyramidalImageWriteStatistics statistics
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

        // Session-local export: the slot is simply (re)written from this
        // session's data, without ever reading what a previous run left there.
        boolean existedBefore = outputFile.isFile();
        if (!writeImage(outputFile, tileImage)) {
            return false;
        }
        if (existedBefore) {
            statistics.incrementRewrittenImages();
        }
        else {
            statistics.incrementNewImages();
        }
        return true;
    }

    private static boolean writeImage(File outputFile, RGBImageUncompressed image) {
        try {
            ImagePersistence.exportPNG(outputFile, image);
        }
        catch (Exception ex) {
            System.out.println("SessionPyramidalImageExportService: could not write " + outputFile + ": " + ex.getMessage());
            return false;
        }
        return true;
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
            System.out.println("SessionPyramidalImageExportService: could not read " + sourcePath + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * Samples the tile's texture sub-rectangle (OpenGL convention: v = 0 at
     * the image bottom, so it is flipped to the source image's top-down
     * raster rows) with nearest-neighbor sampling, matching the GL_NEAREST
     * filtering the interactive renderer uses for the same tiles.
     */
    private static RGBImageUncompressed cropTile(RGBImageUncompressed sourceImage, MatrixLayerTile tile) {
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
            System.out.println("SessionPyramidalImageExportService: could not create directory " + directory + ": " + ex.getMessage());
            return false;
        }
        return Files.isDirectory(directory) && Files.isWritable(directory);
    }
}
