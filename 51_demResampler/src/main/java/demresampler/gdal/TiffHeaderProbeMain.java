package demresampler.gdal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Isolated GDAL header reader. The parent process may forcibly terminate this
 * JVM if a native GDAL call becomes stuck in uninterruptible disk I/O.
 */
public final class TiffHeaderProbeMain {
    private static final String OK = "OK";
    private static final String ERROR_PREFIX = "ERROR ";

    private TiffHeaderProbeMain() {
    }

    public static void main(String[] arguments) throws IOException {
        try (BufferedReader input = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String encodedPath;
            while ((encodedPath = input.readLine()) != null) {
                probe(decode(encodedPath));
            }
        }
    }

    private static void probe(Path path) {
        try (GdalDataset dataset = GdalDataset.open(path)) {
            TiffHeaderMetadata metadata = new TiffHeaderMetadata(
                dataset.describe(),
                dataset.isWgs84Geographic());
            System.out.println(OK + " " + encode(metadata.serialize()));
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            System.out.println(ERROR_PREFIX + encode(message));
        }
        System.out.flush();
    }

    static String encode(String value) {
        return Base64.getEncoder().encodeToString(
            value.getBytes(StandardCharsets.UTF_8));
    }

    static Path decode(String value) {
        byte[] bytes = Base64.getDecoder().decode(value);
        return Path.of(new String(bytes, StandardCharsets.UTF_8));
    }
}
