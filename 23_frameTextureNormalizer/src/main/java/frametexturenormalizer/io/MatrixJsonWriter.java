package frametexturenormalizer.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.contract.ScopedTileIds;
import frametexturenormalizer.processing.uncles.ToUncleRelationship;

public final class MatrixJsonWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private MatrixJsonWriter() {
    }

    private static MatrixJson toJsonMatrix(int frameId, TileMatrix matrix, Map<Integer, String> exportIdByTileId) {
        if (matrix == null) {
            return null;
        }
        List<TileJson> tiles = new ArrayList<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            String id = exportIdByTileId.getOrDefault(tile.tileId(), ScopedTileIds.format(frameId, tile.tileId()));
            tiles.add(new TileJson(
                id,
                tile.i(),
                tile.j(),
                tile.textureFile(),
                toJsonUncles(frameId, tile.uncles(), exportIdByTileId)
            ));
        }
        return new MatrixJson(matrix.getRows(), matrix.getCols(), tiles);
    }

    public static void writeMatricesJson(int frameId, List<TileMatrix> matrices) {
        if (frameId < 0 || matrices == null || matrices.isEmpty()) {
            return;
        }
        Map<Integer, String> exportIdByTileId = buildExportIds(frameId, matrices);
        List<MatrixJson> matrixJsons = new ArrayList<>();
        for (TileMatrix matrix : matrices) {
            if (matrix == null || matrix.getTiles() == null || matrix.getTiles().size() < 2) {
                continue;
            }
            MatrixJson json = toJsonMatrix(frameId, matrix, exportIdByTileId);
            if (json != null && json.tiles() != null && json.tiles().size() >= 2) {
                matrixJsons.add(json);
            }
        }
        if (matrixJsons.isEmpty()) {
            return;
        }
        Path frameDir = Path.of(Configuration.INPUT_PATH, String.format("%05d", frameId));
        Path matrixJson = frameDir.resolve("matrix.json");
        try {
            Files.createDirectories(frameDir);
            JSON.writerWithDefaultPrettyPrinter().writeValue(matrixJson.toFile(), new FrameMatricesJson(3, frameId, matrixJsons));
        }
        catch (IOException ex) {
            System.out.println("Unable to write " + matrixJson + ": " + ex.getMessage());
        }
    }

    private static List<ToUncleRelationshipJson> toJsonUncles(
        int frameId,
        List<ToUncleRelationship> uncles,
        Map<Integer, String> exportIdByTileId
    ) {
        if (uncles == null || uncles.isEmpty()) {
            return List.of();
        }
        List<ToUncleRelationshipJson> out = new ArrayList<>(uncles.size());
        for (ToUncleRelationship relationship : uncles) {
            if (relationship == null || relationship.direction() == null || relationship.uncleContentId() == null) {
                continue;
            }
            String normalizedScopedId = ScopedTileIds.normalize(relationship.uncleContentId());
            String scopedUncleId;
            if (normalizedScopedId != null && normalizedScopedId.contains("_")) {
                Integer localUncleId = localTileId(normalizedScopedId, frameId);
                scopedUncleId = localUncleId == null
                    ? normalizedScopedId
                    : exportIdByTileId.getOrDefault(localUncleId, normalizedScopedId);
            }
            else {
                Integer numericUncleId = extractLastNumber(relationship.uncleContentId(), -1);
                if (numericUncleId < 0) {
                    continue;
                }
                scopedUncleId = exportIdByTileId.get(numericUncleId);
                if (scopedUncleId == null) {
                    scopedUncleId = relationship.uncleContentId();
                }
            }
            out.add(new ToUncleRelationshipJson(
                relationship.direction().name(),
                scopedUncleId,
                relationship.relationshipKind() == null ? null : relationship.relationshipKind().name()
            ));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /**
     * Uses a canonical texture-derived ID only when it identifies exactly one native
     * tile in this frame. Identical pixels are not sufficient evidence that two tile
     * occurrences are the same quadtree node (blank sea tiles are the common case).
     */
    static Map<Integer, String> buildExportIds(int frameId, List<TileMatrix> matrices) {
        Map<Integer, String> nativeByTileId = new HashMap<>();
        Map<Integer, String> candidateByTileId = new HashMap<>();
        Map<String, Integer> candidateCounts = new HashMap<>();
        Map<String, Integer> nativeOwners = new HashMap<>();
        for (TileMatrix matrix : matrices) {
            if (matrix == null || matrix.getTiles() == null) {
                continue;
            }
            for (TileMatrix.TileCoord tile : matrix.getTiles()) {
                if (tile == null || tile.tileId() < 0) {
                    continue;
                }
                String nativeId = ScopedTileIds.format(frameId, tile.tileId());
                int canonicalTileId = extractLastNumber(tile.textureFile(), tile.tileId());
                String candidate = ScopedTileIds.formatFromTextureFile(tile.textureFile(), frameId, canonicalTileId);
                nativeByTileId.put(tile.tileId(), nativeId);
                candidateByTileId.put(tile.tileId(), candidate);
                candidateCounts.merge(candidate, 1, Integer::sum);
                nativeOwners.put(nativeId, tile.tileId());
            }
        }

        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<Integer, String> entry : nativeByTileId.entrySet()) {
            int tileId = entry.getKey();
            String nativeId = entry.getValue();
            String candidate = candidateByTileId.get(tileId);
            Integer nativeOwner = nativeOwners.get(candidate);
            boolean unambiguous = candidate != null
                && candidateCounts.getOrDefault(candidate, 0) == 1
                && (nativeOwner == null || nativeOwner == tileId);
            out.put(tileId, unambiguous ? candidate : nativeId);
        }
        return out;
    }

    private static Integer localTileId(String scopedId, int frameId) {
        if (scopedId == null) {
            return null;
        }
        int separator = scopedId.indexOf('_');
        if (separator <= 0 || separator >= scopedId.length() - 1) {
            return null;
        }
        try {
            if (Integer.parseInt(scopedId.substring(0, separator)) != frameId) {
                return null;
            }
            return Integer.parseInt(scopedId.substring(separator + 1));
        }
        catch (NumberFormatException ignored) {
            return null;
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

    private record MatrixJson(int rows, int cols, List<TileJson> tiles) {
    }

    private record FrameMatricesJson(int contractVersion, int frameId, List<MatrixJson> matrices) {
    }

    private record TileJson(String id, int i, int j, String textureFile, List<ToUncleRelationshipJson> uncles) {
    }

    private record ToUncleRelationshipJson(
        String direction,
        String uncleContentId,
        String relationshipKind
    ) {
    }
}
