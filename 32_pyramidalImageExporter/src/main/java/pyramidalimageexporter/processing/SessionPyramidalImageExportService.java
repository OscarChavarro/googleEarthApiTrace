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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import pyramidalimageexporter.config.Configuration;
import pyramidalimageexporter.diagnostics.PerformanceReport;
import pyramidalimageexporter.io.MatrixLayerIdRewriteWriter;
import pyramidalimageexporter.model.PyramidalImageWriteStatistics;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.model.state.PyramidalImageExporterState;
import pyramidalimageexporter.processing.content.ContentHashCatalog;
import pyramidalimageexporter.processing.content.ReferenceContentRigidAnchorResolver;
import pyramidalimageexporter.processing.geography.FrameCameraGeoAnchorResolver;
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
 * The export never reads its own previous destination. When a read-only
 * reference pyramid is supplied, its levels 0..5 may be copied as fallback
 * scaffold tiles so the delta remains rooted even if the current trace has
 * no globe-level catalogue. Local session tiles retain priority. Merging the
 * remaining capture content is the responsibility of a separate program.
 */
public final class SessionPyramidalImageExportService {
    private static final int TILE_PIXEL_SIZE = 256;
    private static final int PROGRESS_REPORT_INTERVAL = 100;
    private static final double FULL_TEXTURE_RECT_TOLERANCE = 1.0e-9;
    private static final int HIGHEST_RECONSTRUCTED_TOP_LEVEL = 5;
    private static final int DEFAULT_EXPORT_THREADS = Math.min(
        16,
        Math.max(2, Runtime.getRuntime().availableProcessors())
    );

    private final TileRootPathResolver rootPathResolver =
        new TileRootPathResolver(Configuration.captureBoundaryLevel());

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
        if (!PerformanceReport.time("export.ensureDestinationDirectory", () -> ensureDirectory(rootDirectory))) {
            reportStatus(model, "Export failed: destination directory is not accessible: " + rootDirectory);
            return;
        }

        Path outputDirectory = Path.of(Configuration.outputDirectory()).toAbsolutePath().normalize();
        PerformanceReport.time(
            "export.restoreFrameJsonUncleMetadata",
            () -> new FrameJsonUncleMetadataRestorer().enrich(model.getMatrixLayers(), outputDirectory)
        );

        Map<String, String> anchorCatalog = anchorCatalog(model);
        Map<String, String> preliminaryExternalPaths = PerformanceReport.time(
            "export.preliminaryExternalPathsFromCatalog",
            () -> buildExternalUncleFullPaths(anchorCatalog)
        );
        preliminaryExternalPaths.putAll(model.getMergedFullPathByOriginalId());
        ExternalUncleBridgeBuilder.Bridge preliminaryBridge = PerformanceReport.time(
            "export.preliminaryExternalUncleBridge",
            () -> new ExternalUncleBridgeBuilder().build(
                model.getMatrixLayers(),
                anchorCatalog,
                model.getContentHashCatalog(),
                outputDirectory
            )
        );
        preliminaryExternalPaths.putAll(preliminaryBridge.fullPathByExternalId());
        TileRootPathResolver.Resolution preliminaryResolution = PerformanceReport.time(
            "export.preliminaryRootPathResolution",
            () -> rootPathResolver.resolve(
                model.getMatrixLayers(),
                preliminaryExternalPaths,
                preliminaryBridge.aliasById()
            )
        );
        Set<String> structurallyAnchoredLayers = PerformanceReport.time(
            "export.resolveStructurallyAnchoredLayerNames",
            () -> resolvedLayerNames(
                model.getMatrixLayers(),
                preliminaryResolution
            )
        );
        ReferenceContentRigidAnchorResolver.Anchors referenceContentAnchors =
            model.getReferencePyramidFolder() == null
                ? new ReferenceContentRigidAnchorResolver.Anchors(Map.of(), Set.of())
                : PerformanceReport.time(
                    "export.referenceContentRigidAnchors",
                    () -> new ReferenceContentRigidAnchorResolver().resolve(
                        model.getMatrixLayers(),
                        Path.of(model.getReferencePyramidFolder()),
                        preliminaryResolution
                    )
                );
        FrameCameraGeoAnchorResolver.Anchors geographicAnchors = PerformanceReport.time(
            "export.frameCameraGeoAnchors",
            () -> new FrameCameraGeoAnchorResolver().resolve(
                model.getMatrixLayers(),
                outputDirectory,
                model.getReferenceQuadPathsByImagePath(),
                preliminaryResolution
            )
        );
        Set<String> protectedLayers = new HashSet<>(structurallyAnchoredLayers);
        protectedLayers.addAll(referenceContentAnchors.anchoredLayerNames());
        protectedLayers.addAll(geographicAnchors.anchoredLayerNames());
        PerformanceReport.time(
            "export.applyContentHashAnchors",
            () -> applyContentHashAnchors(model, protectedLayers, model.getContentHashCatalog())
        );

        Map<String, String> externalFullPaths = PerformanceReport.time(
            "export.externalPathsFromCatalog",
            () -> buildExternalUncleFullPaths(anchorCatalog)
        );
        externalFullPaths.putAll(model.getMergedFullPathByOriginalId());
        ExternalUncleBridgeBuilder.Bridge bridge = PerformanceReport.time(
            "export.externalUncleBridge",
            () -> new ExternalUncleBridgeBuilder().build(
                model.getMatrixLayers(),
                anchorCatalog,
                model.getContentHashCatalog(),
                outputDirectory
            )
        );
        externalFullPaths.putAll(bridge.fullPathByExternalId());
        Set<String> directlyPlacedLayerNames = new HashSet<>(geographicAnchors.anchoredLayerNames());
        directlyPlacedLayerNames.addAll(referenceContentAnchors.anchoredLayerNames());
        Set<String> directlyPlacedTileIds = tileIdsInLayers(
            model.getMatrixLayers(),
            directlyPlacedLayerNames
        );
        directlyPlacedTileIds.forEach(externalFullPaths::remove);
        externalFullPaths.putAll(geographicAnchors.fullPathByTileId());
        externalFullPaths.putAll(referenceContentAnchors.fullPathByTileId());
        System.out.println(
            "SessionPyramidalImageExportService: " + externalFullPaths.size()
                + " externally anchored id(s) and " + bridge.aliasById().size()
                + " dangling-uncle alias(es) available for root path resolution."
        );
        TileRootPathResolver.Resolution resolution = PerformanceReport.time(
            "export.rootPathResolution",
            () -> rootPathResolver.resolve(model.getMatrixLayers(), externalFullPaths, bridge.aliasById())
        );
        PerformanceReport.time("export.reportPlacement", () -> reportPlacement(model, resolution));
        PerformanceReport.time("export.reportMissingAbsoluteSeed", () -> reportMissingAbsoluteSeed(model, resolution));

        ExportManifest manifest;
        try {
            manifest = PerformanceReport.time("export.buildManifest", () -> buildExportManifest(model, resolution));
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
        if (manifest.entries().stream().noneMatch(entry -> "0".equals(entry.fullPath()))) {
            reportStatus(
                model,
                "Export failed: no native root tile 0.png was available from either the current "
                    + "top-level catalogue or the reference pyramid; the previous pyramid was preserved."
            );
            return;
        }
        if (!PerformanceReport.time("export.clearPreviousExport", () -> clearPreviousExport(rootDirectory))) {
            reportStatus(model, "Export failed: could not clear previous pyramid at " + rootDirectory);
            return;
        }

        int totalTiles = manifest.entries().size();
        int exportThreads = Math.max(
            1,
            Math.min(totalTiles, Integer.getInteger("pyramidalimageexporter.exportThreads", DEFAULT_EXPORT_THREADS))
        );
        System.out.println(
            "SessionPyramidalImageExportService: export starting, " + totalTiles
                + " tiles to write to " + rootDirectory + " using " + exportThreads + " thread(s)"
        );
        PyramidalImageWriteStatistics statistics = new PyramidalImageWriteStatistics();
        int failed = 0;
        int processed = 0;
        boolean interrupted = false;
        ExecutorService executor = Executors.newFixedThreadPool(exportThreads);
        ExecutorCompletionService<Boolean> completedTiles = new ExecutorCompletionService<>(executor);
        try {
            long submitStartNanos = System.nanoTime();
            for (ExportEntry entry : manifest.entries()) {
                completedTiles.submit(() -> exportTile(rootDirectory, entry.fullPath(), entry.tile(), statistics));
            }
            PerformanceReport.addNanos("export.tile.submitAll", System.nanoTime() - submitStartNanos);
            while (processed < totalTiles) {
                Future<Boolean> completed = completedTiles.take();
                try {
                    if (!completed.get()) {
                        failed++;
                    }
                }
                catch (java.util.concurrent.ExecutionException ex) {
                    failed++;
                    System.out.println("SessionPyramidalImageExportService: tile worker failed: " + ex.getCause());
                }
                processed++;
                if (processed % PROGRESS_REPORT_INTERVAL == 0) {
                    System.out.println(
                        "SessionPyramidalImageExportService: processed " + processed + "/" + totalTiles + " tiles..."
                    );
                }
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            interrupted = true;
            failed += totalTiles - processed;
        }
        finally {
            executor.shutdownNow();
        }
        System.out.println("SessionPyramidalImageExportService: " + statistics);
        if (interrupted) {
            reportStatus(model, "Export interrupted after " + processed + "/" + totalTiles + " tiles.");
            return;
        }
        reportStatus(
            model,
            "Export complete: " + processed + " tiles processed to " + rootDirectory
                + (failed > 0 ? " (" + failed + " failed)" : "")
        );
    }

    private static Set<String> resolvedLayerNames(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution
    ) {
        Set<String> out = new HashSet<>();
        for (MatrixLayer layer : layers) {
            if (layer == null || layer.getSourceFolderName() == null || layer.getTiles() == null) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile != null && resolution.pathFor(layer, tile) != null) {
                    out.add(layer.getSourceFolderName());
                    break;
                }
            }
        }
        return out;
    }

    private static Set<String> tileIdsInLayers(
        List<MatrixLayer> layers,
        Set<String> layerNames
    ) {
        Set<String> ids = new HashSet<>();
        for (MatrixLayer layer : layers) {
            if (layer == null || !layerNames.contains(layer.getSourceFolderName())) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile != null && tile.getId() != null) {
                    ids.add(tile.getId());
                }
            }
        }
        return ids;
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

    private static void applyContentHashAnchors(
        PyramidalImageExporterState model,
        Set<String> geographicallyAnchoredLayerNames,
        ContentHashCatalog resolver
    ) {
        String inputFolder = model.getInputFolder();
        if (inputFolder == null) {
            return;
        }
        ContentHashCatalog contentHashCatalog = resolver == null
            ? ContentHashCatalog.build(anchorCatalog(model), model.getReferenceContentHashByImagePath())
            : resolver;

        MatrixLayerIdRewriteWriter rewriter = new MatrixLayerIdRewriteWriter();
        for (MatrixLayer layer : model.getMatrixLayers()) {
            if (layer == null || layer.getTiles() == null || layer.getSourceFolderName() == null) {
                continue;
            }
            if (geographicallyAnchoredLayerNames != null
                && geographicallyAnchoredLayerNames.contains(layer.getSourceFolderName())) {
                continue;
            }
            Map<String, String> newIdByOldId = new HashMap<>();
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile == null || isQuadPath(tile.getId())) {
                    continue;
                }
                Optional<String> resolvedQuadPath = contentHashCatalog.resolveQuadPath(tile.getTextureFile());
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

    private static Map<String, String> anchorCatalog(PyramidalImageExporterState model) {
        Map<String, String> catalog = new LinkedHashMap<>(model.getCataloguedQuadPathsByImagePath());
        catalog.putAll(model.getReferenceQuadPathsByImagePath());
        return catalog;
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

        MatrixLayer referenceTopCatalogLayer = new MatrixLayer();
        referenceTopCatalogLayer.setSourceFolderName("topLevel_reference_catalog");
        Map<String, String> referenceTopCatalog = new TreeMap<>(model.getReferenceQuadPathsByImagePath());
        for (Map.Entry<String, String> catalogEntry : referenceTopCatalog.entrySet()) {
            String fullPath = catalogEntry.getValue();
            if (!isQuadPath(fullPath) || fullPath.length() - 1 > HIGHEST_RECONSTRUCTED_TOP_LEVEL) {
                continue;
            }
            MatrixLayerTile referenceTile = new MatrixLayerTile();
            referenceTile.setId(fullPath);
            referenceTile.setTextureFile(catalogEntry.getKey());
            if (!isNativeExportTile(referenceTile, nativeImageByPath)) {
                rejectedByLevel.merge(fullPath.length() - 1, 1, Integer::sum);
                continue;
            }
            selectedByPath.putIfAbsent(
                fullPath,
                new ExportEntry(referenceTopCatalogLayer, referenceTile, fullPath)
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
            if (!PerformanceReport.time("fs.sameTextureContent.stat", () -> Files.isRegularFile(firstPath))
                || !PerformanceReport.time("fs.sameTextureContent.stat", () -> Files.isRegularFile(secondPath))) {
                return false;
            }
            Long mismatch = PerformanceReport.time("fs.sameTextureContent.mismatch", () -> {
                try {
                    return Files.mismatch(firstPath, secondPath);
                }
                catch (IOException ex) {
                    throw new FileComparisonException(ex);
                }
            });
            PerformanceReport.increment("fs.sameTextureContent.mismatch.count");
            if (mismatch == -1L) {
                return true;
            }
            return sameDecodedPixels(firstPath, secondPath);
        }
        catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean sameDecodedPixels(Path firstPath, Path secondPath) {
        try {
            BufferedImage first = PerformanceReport.time(
                "image.sameDecodedPixels.read",
                () -> {
                    try {
                        return ImageIO.read(firstPath.toFile());
                    }
                    catch (IOException ex) {
                        throw new ImageReadException(ex);
                    }
                }
            );
            BufferedImage second = PerformanceReport.time(
                "image.sameDecodedPixels.read",
                () -> {
                    try {
                        return ImageIO.read(secondPath.toFile());
                    }
                    catch (IOException ex) {
                        throw new ImageReadException(ex);
                    }
                }
            );
            if (first == null || second == null
                || first.getWidth() != second.getWidth()
                || first.getHeight() != second.getHeight()) {
                return false;
            }
            long compareStartNanos = System.nanoTime();
            for (int y = 0; y < first.getHeight(); y++) {
                for (int x = 0; x < first.getWidth(); x++) {
                    if (first.getRGB(x, y) != second.getRGB(x, y)) {
                        PerformanceReport.addNanos("image.sameDecodedPixels.compareRgb", System.nanoTime() - compareStartNanos);
                        return false;
                    }
                }
            }
            PerformanceReport.addNanos("image.sameDecodedPixels.compareRgb", System.nanoTime() - compareStartNanos);
            return true;
        }
        catch (RuntimeException ex) {
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
        if (!PerformanceReport.time("image.is256Square.stat", () -> Files.isRegularFile(path))
            || !PerformanceReport.time("image.is256Square.stat", () -> Files.isReadable(path))) {
            return false;
        }
        try (ImageInputStream input = PerformanceReport.time(
            "image.is256Square.open",
            () -> {
                try {
                    return ImageIO.createImageInputStream(path.toFile());
                }
                catch (IOException ex) {
                    throw new ImageReadException(ex);
                }
            }
        )) {
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
                return PerformanceReport.time(
                    "image.is256Square.readDimensions",
                    () -> {
                        try {
                            return reader.getWidth(0) == TILE_PIXEL_SIZE && reader.getHeight(0) == TILE_PIXEL_SIZE;
                        }
                        catch (IOException ex) {
                            throw new ImageReadException(ex);
                        }
                    }
                );
            }
            finally {
                reader.dispose();
            }
        }
        catch (ImageReadException | IOException ex) {
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
        if (!PerformanceReport.time("export.tile.ensureDirectory", () -> ensureDirectory(tileDirectory))) {
            return false;
        }
        File outputFile = tileDirectory.resolve(fullPath + ".png").toFile();

        // Session-local export: the slot is simply (re)written from this
        // session's data, without ever reading what a previous run left there.
        boolean existedBefore = PerformanceReport.time("export.tile.outputExistsStat", outputFile::isFile);
        try {
            Path source = Path.of(tile.getTextureFile());
            Long sourceSize = PerformanceReport.time("export.tile.sourceSizeStat", () -> {
                try {
                    return Files.size(source);
                }
                catch (IOException ex) {
                    return 0L;
                }
            });
            PerformanceReport.time(
                "export.tile.copy",
                () -> {
                    try {
                        Files.copy(
                            source,
                            outputFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                    catch (IOException ex) {
                        throw new TileCopyException(ex);
                    }
                }
            );
            PerformanceReport.increment("export.tile.copy.count");
            PerformanceReport.incrementBy("export.tile.copy.bytes", sourceSize);
        }
        catch (RuntimeException ex) {
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
            List<Path> pathList = PerformanceReport.time(
                "export.clearPreviousExport.walkAndSort",
                () -> paths.sorted(Comparator.reverseOrder()).toList()
            );
            PerformanceReport.incrementBy("export.clearPreviousExport.paths", pathList.size());
            for (Path path : pathList) {
                if (!path.equals(rootDirectory)) {
                    boolean deleted = PerformanceReport.time(
                        "export.clearPreviousExport.deleteIfExists",
                        () -> {
                            try {
                                return Files.deleteIfExists(path);
                            }
                            catch (IOException ex) {
                                throw new DeleteExportException(ex);
                            }
                        }
                    );
                    if (deleted) {
                        PerformanceReport.increment("export.clearPreviousExport.deleted");
                    }
                }
            }
            return true;
        }
        catch (IOException | DeleteExportException ex) {
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

    private static final class FileComparisonException extends RuntimeException {
        private FileComparisonException(Throwable cause) {
            super(cause);
        }
    }

    private static final class ImageReadException extends RuntimeException {
        private ImageReadException(Throwable cause) {
            super(cause);
        }
    }

    private static final class TileCopyException extends RuntimeException {
        private TileCopyException(Throwable cause) {
            super(cause);
        }
    }

    private static final class DeleteExportException extends RuntimeException {
        private DeleteExportException(Throwable cause) {
            super(cause);
        }
    }
}
