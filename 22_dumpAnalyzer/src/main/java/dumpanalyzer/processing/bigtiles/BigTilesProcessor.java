package dumpanalyzer.processing.bigtiles;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.List;

public final class BigTilesProcessor {
    private static final BigAreaCoveringTile DETECTOR = new BigAreaCoveringTile();

    private BigTilesProcessor() {
    }

    public static void preprocessBigTiles(List<Frame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        for (Frame frame : frames) {
            preprocessFrame(frame);
        }
    }

    private static void preprocessFrame(Frame frame) {
        if (frame == null) {
            return;
        }
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            tile.setBigTile(DETECTOR.groupTilesIntoBigTile(tile));
        }
        BigTileNeighborDetector.populateNeighbors(frame);
    }
}
