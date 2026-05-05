package frametexturenormalizer.processing;

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

    public static List<FrameData> normalize(List<FrameData> frames, List<List<String>> duplicatedTextureGroups) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }

        Map<String, String> canonicalTextureByTexture = buildCanonicalTextureMap(duplicatedTextureGroups);
        List<FrameData> normalizedFrames = new ArrayList<>(frames.size());

        for (FrameData frame : frames) {
            if (frame == null) {
                continue;
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
                    String canonicalTexture = canonicalTextureByTexture.getOrDefault(originalTexture, originalTexture);
                    int newTileId = canonicalTexture == null ? tile.getTileId() : extractLastNumber(canonicalTexture, tile.getTileId());
                    idRemap.put(tile.getTileId(), newTileId);

                    interimTiles.add(new TileInstance(
                        newTileId,
                        tile.getFrameId(),
                        canonicalTexture,
                        tile.getSouthNeighbor(),
                        tile.getNorthNeighbor(),
                        tile.getEastNeighbor(),
                        tile.getWestNeighbor(),
                        tile.getTriangleStrip()
                    ));
                }
            }

            List<TileInstance> normalizedTiles = new ArrayList<>(interimTiles.size());
            for (TileInstance tile : interimTiles) {
                normalizedTiles.add(new TileInstance(
                    tile.getTileId(),
                    tile.getFrameId(),
                    tile.getTextureFile(),
                    remapNeighbor(tile.getSouthNeighbor(), idRemap),
                    remapNeighbor(tile.getNorthNeighbor(), idRemap),
                    remapNeighbor(tile.getEastNeighbor(), idRemap),
                    remapNeighbor(tile.getWestNeighbor(), idRemap),
                    tile.getTriangleStrip()
                ));
            }

            normalizedFrames.add(new FrameData(
                frame.getId(),
                normalizedTiles,
                frame.getLines(),
                frame.getCameraState(),
                frame.getProjectionMatrix(),
                frame.getModelViewMatrix(),
                frame.isWithMatrixErrors()
            ));
        }

        return normalizedFrames;
    }

    private static Integer remapNeighbor(Integer neighborId, Map<Integer, Integer> idRemap) {
        if (neighborId == null) {
            return null;
        }
        return idRemap.getOrDefault(neighborId, neighborId);
    }

    private static Map<String, String> buildCanonicalTextureMap(List<List<String>> duplicatedTextureGroups) {
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
