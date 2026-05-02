package pyramidalimagebuilder.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.model.TileMatrix;

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
            JSON.writerWithDefaultPrettyPrinter().writeValue(matrixJson.toFile(), matrix);
        }
        catch (IOException ex) {
            System.out.println("Unable to write " + matrixJson + ": " + ex.getMessage());
        }
    }
}
