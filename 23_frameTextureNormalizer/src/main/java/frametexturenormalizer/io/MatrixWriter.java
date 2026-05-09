package frametexturenormalizer.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.util.ScopedTileIds;
import processing.uncles.ToUncleRelationship;

public final class MatrixWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MatrixWriter() {
    }

    private static MatrixJson toJsonMatrix(TileMatrix matrix) {
        if (matrix == null) {
            return null;
        }
        List<TileJson> tiles = new ArrayList<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            String id = ScopedTileIds.formatFromTextureFile(tile.textureFile(), matrix.getFrameId(), tile.tileId());
            tiles.add(new TileJson(id, tile.i(), tile.j(), tile.textureFile(), toJsonUncles(tile.uncles())));
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

    private static List<ToUncleRelationshipJson> toJsonUncles(List<ToUncleRelationship> uncles) {
        if (uncles == null || uncles.isEmpty()) {
            return List.of();
        }
        List<ToUncleRelationshipJson> out = new ArrayList<>(uncles.size());
        for (ToUncleRelationship relationship : uncles) {
            if (relationship == null || relationship.direction() == null || relationship.uncleId() == null) {
                continue;
            }
            out.add(new ToUncleRelationshipJson(relationship.direction().name(), relationship.uncleId()));
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private record MatrixJson(int rows, int cols, List<TileJson> tiles) {
    }

    private record TileJson(String id, int i, int j, String textureFile, List<ToUncleRelationshipJson> uncles) {
    }

    private record ToUncleRelationshipJson(String direction, int uncleContentId) {
    }
}
