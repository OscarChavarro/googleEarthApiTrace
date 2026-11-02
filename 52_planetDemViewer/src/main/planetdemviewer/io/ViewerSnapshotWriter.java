package planetdemviewer.io;

import java.nio.file.Files;
import java.nio.file.Path;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;

/** Saves an image captured from the viewer as a PNG file. */
public final class ViewerSnapshotWriter {
    private ViewerSnapshotWriter() {
    }

    public static Path writePng(RGBImageUncompressed image, Path output) {
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImagePersistence.exportPNG(output.toFile(), image);
            return output.toAbsolutePath();
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not write viewer snapshot: " + e.getMessage(), e);
        }
    }
}
