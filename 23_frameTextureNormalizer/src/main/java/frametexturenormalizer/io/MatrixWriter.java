package frametexturenormalizer.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.TileMatrix;

public final class MatrixWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MatrixWriter() {
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

    private static MatrixJson toJsonMatrix(TileMatrix matrix) {
        List<TileJson> tiles = new ArrayList<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            String framePrefix = framePrefixFromTexturePath(tile.textureFile(), matrix.getFrameId());
            String id = framePrefix + "_" + tile.tileId();
            tiles.add(new TileJson(id, tile.i(), tile.j(), tile.textureFile()));
        }
        return new MatrixJson(matrix.getFrameId(), matrix.getRows(), matrix.getCols(), tiles);
    }

    private static String framePrefixFromTexturePath(String textureFile, int fallbackFrameId) {
        if (textureFile != null && !textureFile.isBlank()) {
            try {
                Path texturePath = Paths.get(textureFile);
                Path parent = texturePath.getParent();
                if (parent != null) {
                    String raw = parent.getFileName().toString();
                    if (raw.matches("\\d+")) {
                        return String.format("%05d", Integer.parseInt(raw));
                    }
                }
            }
            catch (Exception ignored) {
                // Fallback below.
            }
        }
        return String.format("%05d", fallbackFrameId);
    }

    private record MatrixJson(int frameId, int rows, int cols, List<TileJson> tiles) {
    }

    private record TileJson(String id, int i, int j, String textureFile) {
    }
}
