package frametexturenormalizer.processing.texture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import processing.uncles.ToUncleRelationship;

public final class TileTextureNormalizer {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private TileTextureNormalizer() {
    }

    public static FrameData normalizeFrame(FrameData frame, Map<String, String> canonicalTextureByTexture) {
        if (frame == null) {
            return null;
        }

        List<TileInstance> originalTiles = frame.getTiles();
        List<TileInstance> interimTiles = new ArrayList<>(originalTiles == null ? 0 : originalTiles.size());
        Map<Integer, Integer> idRemap = new HashMap<>();

        if (originalTiles != null) {
            for (TileInstance tile : originalTiles) {
                if (tile == null) {
                    continue;
                }

                String originalTexture = tile.getTextureFile();
                String canonicalTexture = canonicalTextureByTexture == null
                    ? originalTexture
                    : canonicalTextureByTexture.getOrDefault(originalTexture, originalTexture);
                int newTileId = canonicalTexture == null ? tile.getTileId() : extractLastNumber(canonicalTexture, tile.getTileId());
                idRemap.put(tile.getTileId(), newTileId);

                TileInstance normalizedTile = getTileInstance(tile, newTileId, canonicalTexture);
                interimTiles.add(normalizedTile);
            }
        }

        List<TileInstance> normalizedTiles = new ArrayList<>(interimTiles.size());
        for (TileInstance tile : interimTiles) {
            TileInstance normalizedTile = new TileInstance(
                tile.getTileId(),
                tile.getFrameId(),
                tile.getTextureFile(),
                remapNeighbor(tile.getSouthNeighbor(), idRemap),
                remapNeighbor(tile.getNorthNeighbor(), idRemap),
                remapNeighbor(tile.getEastNeighbor(), idRemap),
                remapNeighbor(tile.getWestNeighbor(), idRemap),
                tile.getTriangleStrip(),
                tile.getModelViewMatrix(),
                tile.getMatrixI(),
                tile.getMatrixJ(),
                tile.isIncorrectMatrixMapping(),
                remapUncles(tile.getUncles(), idRemap)
            );
            normalizedTile.setWestCuttingCell(tile.isWestCuttingCell());
            normalizedTiles.add(normalizedTile);
        }

        return new FrameData(
            frame.getId(),
            normalizedTiles,
            frame.getLines(),
            frame.getCameraState(),
            frame.getProjectionMatrix(),
            frame.getModelViewMatrix(),
            frame.isWithMatrixErrors()
        );
    }

    private static TileInstance getTileInstance(TileInstance tile, int newTileId, String canonicalTexture) {
        TileInstance normalizedTile = new TileInstance(
                newTileId,
            tile.getFrameId(),
                canonicalTexture,
            tile.getSouthNeighbor(),
            tile.getNorthNeighbor(),
            tile.getEastNeighbor(),
            tile.getWestNeighbor(),
            tile.getTriangleStrip(),
            tile.getModelViewMatrix(),
            tile.getMatrixI(),
            tile.getMatrixJ(),
            tile.isIncorrectMatrixMapping(),
            remapUncles(tile.getUncles(), Map.of(tile.getTileId(), newTileId))
        );
        normalizedTile.setWestCuttingCell(tile.isWestCuttingCell());
        return normalizedTile;
    }

    private static Integer remapNeighbor(Integer neighborId, Map<Integer, Integer> idRemap) {
        if (neighborId == null) {
            return null;
        }
        return idRemap.getOrDefault(neighborId, neighborId);
    }

    private static List<ToUncleRelationship> remapUncles(
        List<ToUncleRelationship> uncles,
        Map<Integer, Integer> idRemap
    ) {
        if (uncles == null || uncles.isEmpty()) {
            return List.of();
        }
        List<ToUncleRelationship> out = new ArrayList<>(uncles.size());
        for (ToUncleRelationship relationship : uncles) {
            if (relationship == null || relationship.direction() == null || relationship.uncleId() == null) {
                continue;
            }
            out.add(new ToUncleRelationship(
                relationship.direction(),
                remapNeighbor(relationship.uncleId(), idRemap)
            ));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    public static Map<String, String> buildCanonicalTextureMap(List<List<String>> duplicatedTextureGroups) {
        Map<String, String> result = new HashMap<>();
        if (duplicatedTextureGroups == null) {
            return result;
        }

        for (List<String> group : duplicatedTextureGroups) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            String canonical = selectOldestByFolderNumber(group);
            if (canonical == null) {
                continue;
            }
            for (String texturePath : group) {
                if (texturePath != null && !texturePath.isBlank()) {
                    result.put(texturePath, canonical);
                }
            }
        }

        return result;
    }

    private static String selectOldestByFolderNumber(List<String> group) {
        String best = null;
        int bestFolder = Integer.MAX_VALUE;
        for (String texturePath : group) {
            if (texturePath == null || texturePath.isBlank()) {
                continue;
            }
            int folderNumber = extractParentFolderNumber(texturePath);
            if (best == null || folderNumber < bestFolder || (folderNumber == bestFolder && texturePath.compareTo(best) < 0)) {
                best = texturePath;
                bestFolder = folderNumber;
            }
        }
        return best;
    }

    private static int extractParentFolderNumber(String texturePath) {
        try {
            Path path = Path.of(texturePath);
            Path parent = path.getParent();
            if (parent == null || parent.getFileName() == null) {
                return Integer.MAX_VALUE;
            }
            return extractLastNumber(parent.getFileName().toString(), Integer.MAX_VALUE);
        }
        catch (RuntimeException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static int extractLastNumber(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        Integer last = null;
        while (matcher.find()) {
            last = Integer.parseInt(matcher.group(1));
        }
        return last == null ? fallback : last;
    }
}
