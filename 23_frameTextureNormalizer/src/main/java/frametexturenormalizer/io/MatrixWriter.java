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
import frametexturenormalizer.util.ScopedTileIds;
import processing.uncles.ToUncleRelationship;

public final class MatrixWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private MatrixWriter() {
    }

    private static MatrixJson toJsonMatrix(TileMatrix matrix) {
        if (matrix == null) {
            return null;
        }
        Map<Integer, String> scopedTileIdsByNumericId = buildScopedTileIdsByNumericId(matrix);
        List<TileJson> tiles = new ArrayList<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            String id = ScopedTileIds.formatFromTextureFile(tile.textureFile(), matrix.getFrameId(), tile.tileId());
            tiles.add(new TileJson(
                id,
                tile.i(),
                tile.j(),
                tile.textureFile(),
                toJsonUncles(tile.uncles(), scopedTileIdsByNumericId)
            ));
        }
        return new MatrixJson(matrix.getRows(), matrix.getCols(), tiles);
    }

    public static void writeMatrixJson(TileMatrix matrix) {
        if (matrix == null) {
            return;
        }
        Path frameDir = Path.of(Configuration.INPUT_PATH, String.format("%05d", matrix.getFrameId()));
        Path matrixJson = frameDir.resolve("matrix.json");
        try {
            Files.createDirectories(frameDir);
            JSON.writerWithDefaultPrettyPrinter().writeValue(matrixJson.toFile(), toJsonMatrix(matrix));
        }
        catch (IOException ex) {
            System.out.println("Unable to write " + matrixJson + ": " + ex.getMessage());
        }
    }

    private static Map<Integer, String> buildScopedTileIdsByNumericId(TileMatrix matrix) {
        Map<Integer, String> out = new HashMap<>();
        if (matrix == null || matrix.getTiles() == null) {
            return out;
        }
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null || tile.tileId() < 0) {
                continue;
            }
            String scopedId = ScopedTileIds.formatFromTextureFile(tile.textureFile(), matrix.getFrameId(), tile.tileId());
            if (scopedId != null) {
                out.put(tile.tileId(), scopedId);
            }
        }
        return out;
    }

    private static List<ToUncleRelationshipJson> toJsonUncles(
        List<ToUncleRelationship> uncles,
        Map<Integer, String> scopedTileIdsByNumericId
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
                scopedUncleId = normalizedScopedId;
            }
            else {
                Integer numericUncleId = extractLastNumber(relationship.uncleContentId(), -1);
                if (numericUncleId < 0) {
                    continue;
                }
                scopedUncleId = scopedTileIdsByNumericId.get(numericUncleId);
                if (scopedUncleId == null) {
                    scopedUncleId = relationship.uncleContentId();
                }
            }
            out.add(new ToUncleRelationshipJson(relationship.direction().name(), scopedUncleId));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
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

    private record TileJson(String id, int i, int j, String textureFile, List<ToUncleRelationshipJson> uncles) {
    }

    private record ToUncleRelationshipJson(String direction, String uncleContentId) {
    }
}
