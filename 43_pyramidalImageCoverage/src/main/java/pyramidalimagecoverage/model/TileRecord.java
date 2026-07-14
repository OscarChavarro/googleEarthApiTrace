package pyramidalimagecoverage.model;

import java.nio.file.Path;

public record TileRecord(TileAddress address, Path imagePath) {
}
