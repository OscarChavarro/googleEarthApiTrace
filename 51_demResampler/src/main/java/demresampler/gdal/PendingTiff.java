package demresampler.gdal;

import java.nio.file.Path;

public record PendingTiff(Path path, String reason) {
    public PendingTiff {
        path = path.toAbsolutePath().normalize();
        reason = reason == null || reason.isBlank() ? "unknown header error" : reason;
    }
}
