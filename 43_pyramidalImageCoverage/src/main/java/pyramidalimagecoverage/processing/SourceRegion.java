package pyramidalimagecoverage.processing;

import pyramidalimagecoverage.model.TileRecord;

public record SourceRegion(TileRecord tile, int x0, int y0, int x1, int y1) {
}
