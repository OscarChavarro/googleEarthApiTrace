package demresampler.io;

import java.nio.file.Path;

public record FabdemSourceTile(Path path, int southLatitude, int westLongitude) {
    public int northLatitude() {
        return southLatitude + 1;
    }

    public int eastLongitude() {
        return westLongitude + 1;
    }
}
