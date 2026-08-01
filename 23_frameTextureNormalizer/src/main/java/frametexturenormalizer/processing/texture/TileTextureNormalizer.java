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

public final class TileTextureNormalizer {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private TileTextureNormalizer() {
    }

    public static FrameData normalizeFrame(FrameData frame, Map<String, String> canonicalTextureByTexture) {
        if (frame == null) {
            return null;
        }

        List<TileInstance> originalTiles = frame.getTiles();
        List<TileInstance> normalizedTiles = new ArrayList<>(originalTiles == null ? 0 : originalTiles.size());

        if (originalTiles != null) {
            for (TileInstance tile : originalTiles) {
                if (tile == null) {
                    continue;
                }

                String originalTexture = tile.getTextureFile();
                String canonicalTexture = canonicalTextureByTexture == null
                    ? originalTexture
                    : canonicalTextureByTexture.getOrDefault(originalTexture, originalTexture);
                normalizedTiles.add(copyWithTexture(tile, canonicalTexture));
            }
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

    private static TileInstance copyWithTexture(TileInstance tile, String canonicalTexture) {
        TileInstance normalizedTile = new TileInstance(
            tile.getTileId(),
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
            tile.getUncles(),
            tile.isWestCuttingCell(),
            tile.isSelected()
        );
        return normalizedTile;
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
            String canonical = selectCanonicalTexturePath(group);
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

    public static String selectCanonicalTexturePath(List<String> group) {
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
