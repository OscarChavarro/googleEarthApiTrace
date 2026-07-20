package pyramidalimageexporter.processing;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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

/**
 * Writes this session's reconstructed pyramid to disk as a quadtree of PNG
 * files inside the session's own input folder: the root tile is "0.png" in
 * the destination directory, and every deeper tile "0xy..." lives under one
 * folder per quadrant digit after the root marker, keeping the file name as
 * the full quadkey, e.g. 0/0/0/0000.png. Any tile from any matrix layer is eligible as long
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
    private static final double FULL_TEXTURE_RECT_TOLERANCE = 1.0e-9;

    private final TileRootPathResolver rootPathResolver = new TileRootPathResolver();

    private record ExportEntry(MatrixLayer layer, MatrixLayerTile tile, String fullPath) {}
    private record ExportManifest(
        List<ExportEntry> entries,
        int localReplacementsOfDerivedTop,
        int identicalDuplicateClaims,
        Map<Integer, Integer> rejectedNonNativeTilesByLevel
    ) {}

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
        externalFullPaths.putAll(model.getMergedFullPathByOriginalId());
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
        reportMissingAbsoluteSeed(model, resolution);

        ExportManifest manifest;
        try {
            manifest = buildExportManifest(model, resolution);
        }
        catch (IllegalStateException ex) {
            reportStatus(model, "Export failed before writing: " + ex.getMessage());
            return;
        }
        System.out.println(
            "SessionPyramidalImageExportService: manifest contains " + manifest.entries().size()
                + " unique paths; local tiles replace " + manifest.localReplacementsOfDerivedTop()
                + " derived TOP cells; byte-identical duplicate claims collapsed: "
                + manifest.identicalDuplicateClaims()
                + "; rejected non-native tiles by level: "
                + manifest.rejectedNonNativeTilesByLevel() + "."
        );
        if (manifest.entries().isEmpty()) {
            reportStatus(
                model,
                "Export failed: no native 256x256 tiles with absolute quadtree positions were available; "
                    + "the previous pyramid was preserved."
            );
            return;
        }
        if (!clearPreviousExport(rootDirectory)) {
            reportStatus(model, "Export failed: could not clear previous pyramid at " + rootDirectory);
            return;
        }

        int totalTiles = manifest.entries().size();
        System.out.println("SessionPyramidalImageExportService: export starting, " + totalTiles + " tiles to write to " + rootDirectory);
        PyramidalImageWriteStatistics statistics = new PyramidalImageWriteStatistics();
        int failed = 0;
        int processed = 0;
        for (ExportEntry entry : manifest.entries()) {
            if (!exportTile(rootDirectory, entry.fullPath(), entry.tile(), statistics)) {
                failed++;
            }
            processed++;
            if (processed % PROGRESS_REPORT_INTERVAL == 0) {
                System.out.println(
                    "SessionPyramidalImageExportService: processed " + processed + "/" + totalTiles + " tiles..."
                );
            }
        }
        System.out.println("SessionPyramidalImageExportService: " + statistics);
        reportStatus(
            model,
            "Export complete: " + processed + " tiles processed to " + rootDirectory
                + (failed > 0 ? " (" + failed + " failed)" : "")
        );
    }

    private static void reportMissingAbsoluteSeed(
        PyramidalImageExporterState model,
        TileRootPathResolver.Resolution resolution
    ) {
        if (resolution == null || !resolution.pathById().isEmpty()) {
            return;
        }
        int uncleRelations = 0;
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile != null && tile.getUncles() != null) {
                    uncleRelations += tile.getUncles().size();
                }
            }
        }
        if (uncleRelations > 0) {
            System.out.println(
                "SessionPyramidalImageExportService: loaded " + uncleRelations
                    + " uncle relationship(s), but they only express relative placement. "
                    + "At least one absolute top-level quadkey seed is required before the uncle graph can propagate positions."
            );
        }
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
                String fullPath = resolution.pathFor(layer, tile);
                if (fullPath != null) {
                    placed++;
                    int level = fullPath.length() - 1;
                    levels.add(level);
                    placedTilesByLevel.merge(level, 1, Integer::sum);
                }
                else {
                    if (resolution.isDiscarded(layer, tile)) {
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
        return id != null && id.matches("0[0-3]*");
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
            String fullPath = entry.getValue();
            if (!isQuadPath(fullPath) || fullPath.charAt(0) != '0') {
                System.out.println(
                    "SessionPyramidalImageExportService: ignoring non-absolute catalog path "
                        + fullPath + " for " + imagePath
                );
                continue;
            }
            out.put(frameToken + "_" + tileNumber, fullPath);
            String unpaddedFrameToken = frameToken.replaceFirst("^0+(?=.)", "");
            out.putIfAbsent(unpaddedFrameToken + "_" + tileNumber, fullPath);
        }
        return out;
    }

    private static ExportManifest buildExportManifest(
        PyramidalImageExporterState model,
        TileRootPathResolver.Resolution resolution
    ) {
        Map<String, ExportEntry> selectedByPath = new LinkedHashMap<>();
        Map<String, Boolean> nativeImageByPath = new HashMap<>();
        Map<Integer, Integer> rejectedByLevel = new TreeMap<>();
        int replacements = 0;
        int identicalDuplicates = 0;

        MatrixLayer nativeTopCatalogLayer = new MatrixLayer();
        nativeTopCatalogLayer.setSourceFolderName("topLevel_native_catalog");
        Map<String, String> nativeTopCatalog = new TreeMap<>(model.getCataloguedQuadPathsByImagePath());
        for (Map.Entry<String, String> catalogEntry : nativeTopCatalog.entrySet()) {
            String fullPath = catalogEntry.getValue();
            if (!isQuadPath(fullPath)) {
                continue;
            }
            MatrixLayerTile nativeTile = new MatrixLayerTile();
            nativeTile.setId(fullPath);
            nativeTile.setTextureFile(catalogEntry.getKey());
            if (!isNativeExportTile(nativeTile, nativeImageByPath)) {
                rejectedByLevel.merge(fullPath.length() - 1, 1, Integer::sum);
                continue;
            }
            selectedByPath.putIfAbsent(
                fullPath,
                new ExportEntry(nativeTopCatalogLayer, nativeTile, fullPath)
            );
        }

        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                String fullPath = tile == null ? null : resolution.pathFor(layer, tile);
                if (fullPath == null) {
                    continue;
                }
                if (!isNativeExportTile(tile, nativeImageByPath)) {
                    rejectedByLevel.merge(fullPath.length() - 1, 1, Integer::sum);
                    continue;
                }
                ExportEntry candidate = new ExportEntry(layer, tile, fullPath);
                ExportEntry current = selectedByPath.get(fullPath);
                if (current == null) {
                    selectedByPath.put(fullPath, candidate);
                    continue;
                }
                if (preferCandidateOverCurrent(candidate, current, resolution)) {
                    selectedByPath.put(fullPath, candidate);
                    replacements++;
                    continue;
                }
                if (preferCandidateOverCurrent(current, candidate, resolution)) {
                    continue;
                }
                boolean currentTop = isTopLayer(current.layer());
                boolean candidateTop = isTopLayer(candidate.layer());
                if (currentTop && !candidateTop) {
                    selectedByPath.put(fullPath, candidate);
                    replacements++;
                    continue;
                }
                if (!currentTop && candidateTop) {
                    continue;
                }
                if (current.tile().getId().equals(candidate.tile().getId())) {
                    continue;
                }
                if (sameTextureContent(current.tile(), candidate.tile())) {
                    identicalDuplicates++;
                    continue;
                }
                throw new IllegalStateException(
                    "incompatible duplicate full path " + fullPath
                        + " claimed by " + describe(current) + " and " + describe(candidate)
                );
            }
        }
        return new ExportManifest(
            List.copyOf(selectedByPath.values()),
            replacements,
            identicalDuplicates,
            Map.copyOf(rejectedByLevel)
        );
    }

    private static boolean sameTextureContent(MatrixLayerTile first, MatrixLayerTile second) {
        if (first == null || second == null
            || first.getTextureFile() == null || second.getTextureFile() == null) {
            return false;
        }
        try {
            Path firstPath = Path.of(first.getTextureFile());
            Path secondPath = Path.of(second.getTextureFile());
            if (!Files.isRegularFile(firstPath) || !Files.isRegularFile(secondPath)) {
                return false;
            }
            if (Files.mismatch(firstPath, secondPath) == -1L) {
                return true;
            }
            return sameDecodedPixels(firstPath, secondPath);
        }
        catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static boolean sameDecodedPixels(Path firstPath, Path secondPath) {
        try {
            BufferedImage first = ImageIO.read(firstPath.toFile());
            BufferedImage second = ImageIO.read(secondPath.toFile());
            if (first == null || second == null
                || first.getWidth() != second.getWidth()
                || first.getHeight() != second.getHeight()) {
                return false;
            }
            for (int y = 0; y < first.getHeight(); y++) {
                for (int x = 0; x < first.getWidth(); x++) {
                    if (first.getRGB(x, y) != second.getRGB(x, y)) {
                        return false;
                    }
                }
            }
            return true;
        }
        catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static boolean isNativeExportTile(MatrixLayerTile tile, Map<String, Boolean> nativeImageByPath) {
        if (!usesWholeTexture(tile)) {
            return false;
        }
        String textureFile = tile.getTextureFile();
        if (textureFile == null || textureFile.isBlank()) {
            return false;
        }
        return nativeImageByPath.computeIfAbsent(textureFile, SessionPyramidalImageExportService::is256SquareImage);
    }

    private static boolean usesWholeTexture(MatrixLayerTile tile) {
        return closeTo(tile.getTexU0(), 0.0)
            && closeTo(tile.getTexV0(), 0.0)
            && closeTo(tile.getTexU1(), 1.0)
            && closeTo(tile.getTexV1(), 1.0);
    }

    private static boolean closeTo(double value, double expected) {
        return Math.abs(value - expected) <= FULL_TEXTURE_RECT_TOLERANCE;
    }

    private static boolean is256SquareImage(String textureFile) {
        Path path;
        try {
            path = Path.of(textureFile);
        }
        catch (RuntimeException ex) {
            return false;
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return false;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return reader.getWidth(0) == TILE_PIXEL_SIZE && reader.getHeight(0) == TILE_PIXEL_SIZE;
            }
            finally {
                reader.dispose();
            }
        }
        catch (IOException ex) {
            return false;
        }
    }

    private static boolean preferCandidateOverCurrent(
        ExportEntry candidate,
        ExportEntry current,
        TileRootPathResolver.Resolution resolution
    ) {
        TileRootPathResolver.PathSource candidateSource = resolution.sourceFor(candidate.layer(), candidate.tile());
        TileRootPathResolver.PathSource currentSource = resolution.sourceFor(current.layer(), current.tile());
        return isDirect(candidateSource) && isDerived(currentSource);
    }

    private static boolean isDirect(TileRootPathResolver.PathSource source) {
        return source == TileRootPathResolver.PathSource.DIRECT;
    }

    private static boolean isDerived(TileRootPathResolver.PathSource source) {
        return source == TileRootPathResolver.PathSource.UNCLE || source == TileRootPathResolver.PathSource.GRID;
    }

    private static boolean isTopLayer(MatrixLayer layer) {
        return layer != null
            && layer.getSourceFolderName() != null
            && layer.getSourceFolderName().startsWith("topLevel_matrix_");
    }

    private static String describe(ExportEntry entry) {
        String layer = entry.layer() == null ? "<unknown layer>" : entry.layer().getSourceFolderName();
        return layer + "/" + entry.tile().getId();
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
        Path tileDirectory = directoryFor(rootDirectory, fullPath);
        if (!ensureDirectory(tileDirectory)) {
            return false;
        }
        File outputFile = tileDirectory.resolve(fullPath + ".png").toFile();

        // Session-local export: the slot is simply (re)written from this
        // session's data, without ever reading what a previous run left there.
        boolean existedBefore = outputFile.isFile();
        try {
            Files.copy(
                Path.of(tile.getTextureFile()),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            );
        }
        catch (IOException | RuntimeException ex) {
            System.out.println(
                "SessionPyramidalImageExportService: could not copy native tile to "
                    + outputFile + ": " + ex.getMessage()
            );
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

    private static boolean clearPreviousExport(Path rootDirectory) {
        try (var paths = Files.walk(rootDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(rootDirectory)) {
                    Files.deleteIfExists(path);
                }
            }
            return true;
        }
        catch (IOException ex) {
            System.out.println(
                "SessionPyramidalImageExportService: could not clear previous export at "
                    + rootDirectory + ": " + ex.getMessage()
            );
            return false;
        }
    }

    /**
     * The root tile "0" is written directly in rootDirectory; any deeper
     * tile "0xy..." lives under one folder per quadrant digit after the root
     * marker, while the file keeps the complete quadkey
     * (e.g. quadkey "0021" resolves to rootDirectory/0/2/1/0021.png).
     */
    private static Path directoryFor(Path rootDirectory, String fullId) {
        Path directory = rootDirectory;
        for (int index = 1; index < fullId.length(); index++) {
            directory = directory.resolve(String.valueOf(fullId.charAt(index)));
        }
        return directory;
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
