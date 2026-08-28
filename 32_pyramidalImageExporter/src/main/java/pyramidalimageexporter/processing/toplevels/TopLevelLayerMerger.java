package pyramidalimageexporter.processing.toplevels;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pyramidalimageexporter.config.Configuration;
import pyramidalimageexporter.diagnostics.PerformanceReport;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.content.ContentHashCatalog;
import pyramidalimageexporter.processing.uncles.ExternalUncleBridgeBuilder;
import pyramidalimageexporter.processing.uncles.FrameJsonUncleMetadataRestorer;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

public final class TopLevelLayerMerger {
    private static final int MAX_TOP_LEVEL = 5;

    public record MergeResult(List<MatrixLayer> layers, Map<String, String> mergedFullPathByOriginalId) {}

    public MergeResult merge(
        List<MatrixLayer> inferredTopLayers,
        List<MatrixLayer> importedLayers,
        Map<String, String> cataloguedQuadPathsByImagePath,
        Path outputDirectory
    ) {
        ContentHashCatalog contentHashCatalog = ContentHashCatalog.build(
            cataloguedQuadPathsByImagePath == null ? Map.of() : cataloguedQuadPathsByImagePath,
            Map.of()
        );
        return merge(inferredTopLayers, importedLayers, cataloguedQuadPathsByImagePath, contentHashCatalog, outputDirectory);
    }

    public MergeResult merge(
        List<MatrixLayer> inferredTopLayers,
        List<MatrixLayer> importedLayers,
        Map<String, String> cataloguedQuadPathsByImagePath,
        ContentHashCatalog contentHashCatalog,
        Path outputDirectory
    ) {
        List<MatrixLayer> inferredCopies = copyLayers(inferredTopLayers);
        if (importedLayers == null || importedLayers.isEmpty()) {
            return new MergeResult(inferredCopies, Map.of());
        }

        List<MatrixLayer> importedCopies = copyLayers(importedLayers);
        if (outputDirectory != null) {
            new FrameJsonUncleMetadataRestorer().enrich(importedCopies, outputDirectory);
        }

        Map<String, String> externalFullPaths = PerformanceReport.time("topLevelMerge.coverageAnchors", () -> buildCoverageAnchors(
            importedCopies,
            contentHashCatalog
        ));
        Map<String, String> cataloguedUnclePaths = PerformanceReport.time(
            "topLevelMerge.externalUncleFullPaths",
            () -> buildExternalUncleFullPaths(cataloguedQuadPathsByImagePath)
        );
        externalFullPaths.putAll(cataloguedUnclePaths);

        ExternalUncleBridgeBuilder.Bridge bridge = PerformanceReport.time(
            "topLevelMerge.externalUncleBridge",
            () -> new ExternalUncleBridgeBuilder().build(
                importedCopies,
                cataloguedQuadPathsByImagePath == null ? Map.of() : cataloguedQuadPathsByImagePath,
                contentHashCatalog,
                outputDirectory
            )
        );
        externalFullPaths.putAll(bridge.fullPathByExternalId());

        Map<String, String> topVisualFullPaths = PerformanceReport.time(
            "topLevelMerge.topVisualAnchors",
            () -> new TopLevelVisualAnchorResolver().resolve(inferredCopies, importedCopies)
        );
        Map<String, String> externalVisualUnclePaths = PerformanceReport.time(
            "topLevelMerge.externalVisualUncleAnchors",
            () -> new TopLevelVisualAnchorResolver().resolveExternalTextures(
                inferredCopies,
                bridge.texturePathByExternalId(),
                buildNearbyExternalUncleCandidates(importedCopies, topVisualFullPaths)
            )
        );
        externalVisualUnclePaths.forEach(externalFullPaths::putIfAbsent);
        suppressVisualFallbackForExternallyAnchoredLayers(
            topVisualFullPaths,
            importedCopies,
            externalVisualUnclePaths.keySet()
        );
        externalFullPaths.putAll(topVisualFullPaths);

        TileRootPathResolver pathResolver = new TileRootPathResolver(Configuration.captureBoundaryLevel());
        TileRootPathResolver.Resolution resolution;
        Map<String, String> visualDescendantFullPaths = new LinkedHashMap<>();
        Set<String> replaceableExternalVisualIds = new LinkedHashSet<>(externalVisualUnclePaths.keySet());
        int iteration = 0;
        int deepestCataloguedLevel = deepestCataloguedLevel(cataloguedQuadPathsByImagePath);
        while (true) {
            iteration++;
            int currentIteration = iteration;
            resolution = PerformanceReport.time(
                "topLevelMerge.iteration.rootPathResolution",
                () -> pathResolver.resolve(importedCopies, externalFullPaths, bridge.aliasById())
            );
            TileRootPathResolver.Resolution currentResolution = resolution;
            ImportedLayerVisualAnchorResolver visualResolver = new ImportedLayerVisualAnchorResolver();
            Map<String, String> unresolvedExternalTextures = PerformanceReport.time(
                "topLevelMerge.iteration.unresolvedExternalTextures",
                () -> referencedExternalTexturesOfUnresolvedLayers(
                    importedCopies,
                    currentResolution,
                    bridge.texturePathByExternalId(),
                    externalFullPaths.keySet(),
                    replaceableExternalVisualIds
                )
            );
            Map<String, String> externalDescendantAnchors = PerformanceReport.time(
                "topLevelMerge.iteration.externalDescendantAnchors",
                () -> visualResolver.resolveExternalTextures(
                    unresolvedExternalTextures,
                    importedCopies,
                    currentResolution,
                    deepestCataloguedLevel - 1
                )
            );
            if (!externalDescendantAnchors.isEmpty()) {
                PerformanceReport.incrementBy("topLevelMerge.iteration.externalDescendantAnchors.found", externalDescendantAnchors.size());
                externalFullPaths.putAll(externalDescendantAnchors);
                visualDescendantFullPaths.putAll(externalDescendantAnchors);
                replaceableExternalVisualIds.removeAll(externalDescendantAnchors.keySet());
                continue;
            }
            Map<String, String> visualDescendantAnchors = PerformanceReport.time(
                "topLevelMerge.iteration.visualDescendantAnchors",
                () -> visualResolver.resolve(
                    importedCopies,
                    currentResolution,
                    cataloguedQuadPathsByImagePath
                )
            );
            visualDescendantAnchors.keySet().removeAll(externalFullPaths.keySet());
            if (visualDescendantAnchors.isEmpty()) {
                PerformanceReport.incrementBy("topLevelMerge.iterations", currentIteration);
                break;
            }
            PerformanceReport.incrementBy("topLevelMerge.iteration.visualDescendantAnchors.found", visualDescendantAnchors.size());
            externalFullPaths.putAll(visualDescendantAnchors);
            visualDescendantFullPaths.putAll(visualDescendantAnchors);
        }
        Map<String, MatrixLayerTile> importedTileByTopPath = collectImportedTopTiles(importedCopies, resolution);
        if (importedTileByTopPath.isEmpty()) {
            inferredCopies.addAll(importedCopies);
            Map<String, String> retainedFullPaths = new LinkedHashMap<>(externalVisualUnclePaths);
            retainedFullPaths.putAll(topVisualFullPaths);
            retainedFullPaths.putAll(visualDescendantFullPaths);
            return new MergeResult(inferredCopies, Map.copyOf(retainedFullPaths));
        }

        Set<String> mergedTopPaths = replaceInferredTiles(inferredCopies, importedTileByTopPath);
        List<MatrixLayer> remainingImportedLayers = removeMergedImportedTiles(
            importedCopies,
            resolution,
            mergedTopPaths,
            referencedParentFolders(importedCopies)
        );

        List<MatrixLayer> mergedLayers = new ArrayList<>(inferredCopies.size() + remainingImportedLayers.size());
        mergedLayers.addAll(inferredCopies);
        mergedLayers.addAll(remainingImportedLayers);
        Map<String, String> mergedFullPathByOriginalId = collectMergedAliases(
            importedCopies,
            resolution,
            mergedTopPaths
        );
        mergedFullPathByOriginalId.putAll(externalVisualUnclePaths);
        mergedFullPathByOriginalId.putAll(topVisualFullPaths);
        mergedFullPathByOriginalId.putAll(visualDescendantFullPaths);
        System.out.println(
            "TopLevelLayerMerger: merged " + mergedTopPaths.size()
                + " imported tile(s) into reconstructed top-level matrices; retained "
                + remainingImportedLayers.size() + " imported layer(s)."
        );
        return new MergeResult(mergedLayers, Map.copyOf(mergedFullPathByOriginalId));
    }

    private static Map<String, String> referencedExternalTexturesOfUnresolvedLayers(
        List<MatrixLayer> layers,
        TileRootPathResolver.Resolution resolution,
        Map<String, String> texturePathByExternalId,
        Set<String> alreadyAnchoredIds,
        Set<String> replaceableAnchoredIds
    ) {
        Map<String, String> out = new LinkedHashMap<>();
        for (MatrixLayer layer : layers) {
            boolean unresolved = layer.getTiles().stream()
                .anyMatch(tile -> resolution.pathFor(layer, tile) == null);
            if (!unresolved) {
                continue;
            }
            for (MatrixLayerTile tile : layer.getTiles()) {
                for (var relation : tile.getUncles()) {
                    String id = relation == null ? null : relation.uncleContentId();
                    String texture = id == null ? null : texturePathByExternalId.get(id);
                    if (texture != null
                        && (!alreadyAnchoredIds.contains(id) || replaceableAnchoredIds.contains(id))) {
                        out.putIfAbsent(id, texture);
                    }
                }
            }
        }
        return out;
    }

    private static int deepestCataloguedLevel(Map<String, String> pathsByImage) {
        return pathsByImage.values().stream()
            .filter(path -> path != null && path.matches("0[0-3]*"))
            .mapToInt(path -> path.length() - 1)
            .max()
            .orElse(Integer.MAX_VALUE);
    }

    private static Map<String, String> collectMergedAliases(
        List<MatrixLayer> importedLayers,
        TileRootPathResolver.Resolution resolution,
        Set<String> mergedTopPaths
    ) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (MatrixLayer layer : importedLayers) {
            for (MatrixLayerTile tile : layer.getTiles()) {
                String fullPath = resolution.pathFor(layer, tile);
                if (mergedTopPaths.contains(fullPath)) {
                    aliases.put(tile.getId(), fullPath);
                }
            }
        }
        return aliases;
    }

    private static Map<String, MatrixLayerTile> collectImportedTopTiles(
        List<MatrixLayer> importedLayers,
        TileRootPathResolver.Resolution resolution
    ) {
        Map<String, MatrixLayerTile> importedByPath = new LinkedHashMap<>();
        for (MatrixLayer layer : importedLayers) {
            for (MatrixLayerTile tile : layer.getTiles()) {
                String fullPath = resolution.pathFor(layer, tile);
                if (isTopPath(fullPath)) {
                    importedByPath.putIfAbsent(fullPath, tile);
                }
            }
        }
        return importedByPath;
    }

    private static Set<String> replaceInferredTiles(
        List<MatrixLayer> inferredLayers,
        Map<String, MatrixLayerTile> importedTileByTopPath
    ) {
        Set<String> mergedPaths = new LinkedHashSet<>();
        for (MatrixLayer layer : inferredLayers) {
            List<MatrixLayerTile> tiles = new ArrayList<>();
            for (MatrixLayerTile inferredTile : layer.getTiles()) {
                MatrixLayerTile importedTile = importedTileByTopPath.get(inferredTile.getId());
                if (importedTile == null) {
                    tiles.add(inferredTile);
                    continue;
                }
                MatrixLayerTile replacement = copyTile(inferredTile);
                replacement.setTextureFile(importedTile.getTextureFile());
                replacement.setTextureSubRect(
                    importedTile.getTexU0(),
                    importedTile.getTexV0(),
                    importedTile.getTexU1(),
                    importedTile.getTexV1()
                );
                tiles.add(replacement);
                mergedPaths.add(inferredTile.getId());
            }
            layer.setTiles(tiles);
        }
        return mergedPaths;
    }

    private static List<MatrixLayer> removeMergedImportedTiles(
        List<MatrixLayer> importedLayers,
        TileRootPathResolver.Resolution resolution,
        Set<String> mergedTopPaths,
        Set<String> referencedParentFolders
    ) {
        List<MatrixLayer> remainingLayers = new ArrayList<>();
        for (MatrixLayer layer : importedLayers) {
            if (referencedParentFolders.contains(layer.getSourceFolderName())) {
                remainingLayers.add(copyLayer(layer));
                continue;
            }
            MatrixLayer remainingLayer = copyLayerWithoutTiles(layer);
            List<MatrixLayerTile> remainingTiles = new ArrayList<>();
            for (MatrixLayerTile tile : layer.getTiles()) {
                String fullPath = resolution.pathFor(layer, tile);
                if (mergedTopPaths.contains(fullPath)) {
                    continue;
                }
                remainingTiles.add(copyTile(tile));
            }
            if (!remainingTiles.isEmpty()) {
                remainingLayer.setTiles(remainingTiles);
                remainingLayers.add(remainingLayer);
            }
        }
        return remainingLayers;
    }

    private static Set<String> referencedParentFolders(List<MatrixLayer> importedLayers) {
        Set<String> folders = new LinkedHashSet<>();
        for (MatrixLayer layer : importedLayers) {
            Integer parentIndex = layer == null ? null : layer.getParentMatrixIndex();
            if (parentIndex != null && parentIndex >= 0) {
                folders.add("matrix_" + parentIndex);
            }
        }
        return folders;
    }

    private static boolean isTopPath(String fullPath) {
        return fullPath != null && fullPath.length() - 1 >= 0 && fullPath.length() - 1 <= MAX_TOP_LEVEL;
    }

    private static Map<String, String> buildCoverageAnchors(
        List<MatrixLayer> importedLayers,
        ContentHashCatalog contentHashCatalog
    ) {
        Map<String, String> out = new HashMap<>();
        ContentHashCatalog resolver = contentHashCatalog == null
            ? ContentHashCatalog.build(Map.of(), Map.of())
            : contentHashCatalog;
        for (MatrixLayer layer : importedLayers) {
            for (MatrixLayerTile tile : layer.getTiles()) {
                if (tile.getId() == null || tile.getId().isBlank()) {
                    continue;
                }
                if (isQuadPath(tile.getId())) {
                    out.put(tile.getId(), tile.getId());
                    continue;
                }
                resolver.resolveQuadPath(tile.getTextureFile()).ifPresent(path -> out.put(tile.getId(), path));
            }
        }
        return out;
    }

    private static boolean isQuadPath(String id) {
        return id != null && id.matches("0[0-3]*");
    }

    private static Map<String, String> buildExternalUncleFullPaths(Map<String, String> quadLabelByImagePath) {
        Map<String, String> out = new HashMap<>();
        if (quadLabelByImagePath == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : quadLabelByImagePath.entrySet()) {
            Path imagePath = Path.of(entry.getKey());
            Path frameDirectory = imagePath.getParent();
            if (frameDirectory == null || frameDirectory.getFileName() == null) {
                continue;
            }
            String fileName = imagePath.getFileName().toString();
            if (!fileName.startsWith("256x256_") || !fileName.endsWith(".png")) {
                continue;
            }
            String tileNumber = fileName.substring("256x256_".length(), fileName.length() - ".png".length());
            String frameToken = frameDirectory.getFileName().toString();
            String fullPath = entry.getValue();
            if (!isQuadPath(fullPath)) {
                continue;
            }
            out.put(frameToken + "_" + tileNumber, fullPath);
            out.putIfAbsent(frameToken.replaceFirst("^0+(?=.)", "") + "_" + tileNumber, fullPath);
        }
        return out;
    }

    private static List<MatrixLayer> copyLayers(List<MatrixLayer> layers) {
        List<MatrixLayer> out = new ArrayList<>();
        if (layers == null) {
            return out;
        }
        for (MatrixLayer layer : layers) {
            if (layer != null) {
                out.add(copyLayer(layer));
            }
        }
        return out;
    }

    private static MatrixLayer copyLayer(MatrixLayer source) {
        MatrixLayer copy = copyLayerWithoutTiles(source);
        List<MatrixLayerTile> tiles = new ArrayList<>();
        for (MatrixLayerTile tile : source.getTiles()) {
            if (tile != null) {
                tiles.add(copyTile(tile));
            }
        }
        copy.setTiles(tiles);
        return copy;
    }

    private static MatrixLayer copyLayerWithoutTiles(MatrixLayer source) {
        MatrixLayer copy = new MatrixLayer();
        copy.setContractVersion(source.getContractVersion());
        copy.setHierarchyLevel(source.getHierarchyLevel());
        copy.setParentMatrixIndex(source.getParentMatrixIndex());
        copy.setParentGridTransform(source.getParentGridTransform());
        copy.setFrameId(source.getFrameId());
        copy.setRows(source.getRows());
        copy.setCols(source.getCols());
        copy.setSourceFolderName(source.getSourceFolderName());
        copy.setHierarchyUnclesByTileId(source.getHierarchyUnclesByTileId());
        copy.setHierarchyRelationshipsByTileId(source.getHierarchyRelationshipsByTileId());
        copy.setExternalUncleTextureFilesById(source.getExternalUncleTextureFilesById());
        return copy;
    }

    private static void suppressVisualFallbackForExternallyAnchoredLayers(
        Map<String, String> topVisualFullPaths,
        List<MatrixLayer> layers,
        Set<String> visuallyAnchoredUncleIds
    ) {
        if (topVisualFullPaths.isEmpty() || visuallyAnchoredUncleIds.isEmpty()) {
            return;
        }
        for (MatrixLayer layer : layers) {
            boolean hasExternalAnchor = layer.getTiles().stream()
                .flatMap(tile -> tile.getUncles().stream())
                .anyMatch(relation -> relation != null
                    && visuallyAnchoredUncleIds.contains(relation.uncleContentId()));
            if (hasExternalAnchor) {
                layer.getTiles().forEach(tile -> topVisualFullPaths.remove(tile.getId()));
            }
        }
    }

    private static Map<String, Set<String>> buildNearbyExternalUncleCandidates(
        List<MatrixLayer> layers,
        Map<String, String> visualChildPaths
    ) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (MatrixLayer layer : layers) {
            for (MatrixLayerTile tile : layer.getTiles()) {
                String childPath = visualChildPaths.get(tile.getId());
                int[] child = decodeFullPath(childPath);
                if (child == null) {
                    continue;
                }
                for (var relation : tile.getUncles()) {
                    if (relation == null || relation.uncleContentId() == null) {
                        continue;
                    }
                    // Current captures can retain the relationship semantics but
                    // reference a canonical texture from the neighboring LOD.
                    // Search both legal uncle levels locally; TileRootPathResolver
                    // still applies the declared relationship kind afterwards.
                    if (relation.hasGridOffset()) {
                        int scale = 1 << relation.levelDelta();
                        addNearbyPaths(
                            out,
                            relation.uncleContentId(),
                            child[0] - relation.levelDelta(),
                            Math.floorDiv(child[1] - relation.rowOffset(), scale),
                            Math.floorDiv(child[2] - relation.columnOffset(), scale)
                        );
                        continue;
                    }
                    addNearbyPaths(out, relation.uncleContentId(), child[0] - 1, child[1] / 2, child[2] / 2);
                    addNearbyPaths(out, relation.uncleContentId(), child[0], child[1], child[2]);
                }
            }
        }
        return out;
    }

    private static void addNearbyPaths(
        Map<String, Set<String>> out,
        String uncleId,
        int level,
        int centerRow,
        int centerCol
    ) {
        if (level < 0 || level > MAX_TOP_LEVEL) {
            return;
        }
        int side = 1 << level;
        Set<String> paths = out.computeIfAbsent(uncleId, ignored -> new LinkedHashSet<>());
        for (int rowDelta = -4; rowDelta <= 4; rowDelta++) {
            int row = centerRow + rowDelta;
            if (row < 0 || row >= side) {
                continue;
            }
            for (int colDelta = -4; colDelta <= 4; colDelta++) {
                int col = Math.floorMod(centerCol + colDelta, side);
                paths.add(encodeFullPath(level, row, col));
            }
        }
    }

    private static int[] decodeFullPath(String path) {
        if (path == null || !path.matches("0[0-3]*")) {
            return null;
        }
        int row = 0;
        int col = 0;
        for (int index = 1; index < path.length(); index++) {
            row <<= 1;
            col <<= 1;
            switch (path.charAt(index)) {
                case '0' -> row++;
                case '1' -> { row++; col++; }
                case '2' -> col++;
                case '3' -> { }
                default -> { return null; }
            }
        }
        return new int[]{path.length() - 1, row, col};
    }

    private static String encodeFullPath(int level, int row, int col) {
        StringBuilder path = new StringBuilder("0");
        for (int depth = level - 1; depth >= 0; depth--) {
            boolean south = ((row >> depth) & 1) == 1;
            boolean east = ((col >> depth) & 1) == 1;
            path.append(south ? (east ? '1' : '0') : (east ? '2' : '3'));
        }
        return path.toString();
    }

    private static MatrixLayerTile copyTile(MatrixLayerTile source) {
        MatrixLayerTile copy = new MatrixLayerTile();
        copy.setId(source.getId());
        copy.setI(source.getI());
        copy.setJ(source.getJ());
        copy.setTextureFile(source.getTextureFile());
        copy.setTextureSubRect(source.getTexU0(), source.getTexV0(), source.getTexU1(), source.getTexV1());
        copy.setUncles(source.getUncles());
        return copy;
    }
}
