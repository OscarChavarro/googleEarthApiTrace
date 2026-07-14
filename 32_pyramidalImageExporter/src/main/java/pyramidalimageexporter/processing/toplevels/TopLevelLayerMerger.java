package pyramidalimageexporter.processing.toplevels;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.content.ContentHashRootPathResolver;
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
        List<MatrixLayer> inferredCopies = copyLayers(inferredTopLayers);
        if (importedLayers == null || importedLayers.isEmpty()) {
            return new MergeResult(inferredCopies, Map.of());
        }

        List<MatrixLayer> importedCopies = copyLayers(importedLayers);
        if (outputDirectory != null) {
            new FrameJsonUncleMetadataRestorer().enrich(importedCopies, outputDirectory);
        }

        Map<String, String> externalFullPaths = buildCoverageAnchors(
            importedCopies,
            cataloguedQuadPathsByImagePath
        );
        Map<String, String> cataloguedUnclePaths = buildExternalUncleFullPaths(cataloguedQuadPathsByImagePath);
        externalFullPaths.putAll(cataloguedUnclePaths);

        ExternalUncleBridgeBuilder.Bridge bridge = new ExternalUncleBridgeBuilder().build(
            importedCopies,
            cataloguedQuadPathsByImagePath == null ? Map.of() : cataloguedQuadPathsByImagePath,
            outputDirectory
        );
        externalFullPaths.putAll(bridge.fullPathByExternalId());

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver()
            .resolve(importedCopies, externalFullPaths, bridge.aliasById());
        Map<String, MatrixLayerTile> importedTileByTopPath = collectImportedTopTiles(importedCopies, resolution);
        if (importedTileByTopPath.isEmpty()) {
            inferredCopies.addAll(importedCopies);
            return new MergeResult(inferredCopies, Map.of());
        }

        Set<String> mergedTopPaths = replaceInferredTiles(inferredCopies, importedTileByTopPath);
        List<MatrixLayer> remainingImportedLayers = removeMergedImportedTiles(
            importedCopies,
            resolution,
            mergedTopPaths
        );

        List<MatrixLayer> mergedLayers = new ArrayList<>(inferredCopies.size() + remainingImportedLayers.size());
        mergedLayers.addAll(inferredCopies);
        mergedLayers.addAll(remainingImportedLayers);
        Map<String, String> mergedFullPathByOriginalId = collectMergedAliases(
            importedCopies,
            resolution,
            mergedTopPaths
        );
        System.out.println(
            "TopLevelLayerMerger: merged " + mergedTopPaths.size()
                + " imported tile(s) into reconstructed top-level matrices; retained "
                + remainingImportedLayers.size() + " imported layer(s)."
        );
        return new MergeResult(mergedLayers, Map.copyOf(mergedFullPathByOriginalId));
    }

    private static Map<String, String> collectMergedAliases(
        List<MatrixLayer> importedLayers,
        TileRootPathResolver.Resolution resolution,
        Set<String> mergedTopPaths
    ) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (MatrixLayer layer : importedLayers) {
            for (MatrixLayerTile tile : layer.getTiles()) {
                String fullPath = resolution.pathById().get(tile.getId());
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
                String fullPath = resolution.pathById().get(tile.getId());
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
        Set<String> mergedTopPaths
    ) {
        List<MatrixLayer> remainingLayers = new ArrayList<>();
        for (MatrixLayer layer : importedLayers) {
            MatrixLayer remainingLayer = copyLayerWithoutTiles(layer);
            List<MatrixLayerTile> remainingTiles = new ArrayList<>();
            for (MatrixLayerTile tile : layer.getTiles()) {
                String fullPath = resolution.pathById().get(tile.getId());
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

    private static boolean isTopPath(String fullPath) {
        return fullPath != null && fullPath.length() - 1 >= 0 && fullPath.length() - 1 <= MAX_TOP_LEVEL;
    }

    private static Map<String, String> buildCoverageAnchors(
        List<MatrixLayer> importedLayers,
        Map<String, String> cataloguedQuadPathsByImagePath
    ) {
        Map<String, String> out = new HashMap<>();
        ContentHashRootPathResolver resolver = new ContentHashRootPathResolver();
        resolver.indexCataloguedImages(cataloguedQuadPathsByImagePath == null ? Map.of() : cataloguedQuadPathsByImagePath);
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
        copy.setFrameId(source.getFrameId());
        copy.setRows(source.getRows());
        copy.setCols(source.getCols());
        copy.setSourceFolderName(source.getSourceFolderName());
        copy.setHierarchyUnclesByTileId(source.getHierarchyUnclesByTileId());
        copy.setHierarchyRelationshipsByTileId(source.getHierarchyRelationshipsByTileId());
        return copy;
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
