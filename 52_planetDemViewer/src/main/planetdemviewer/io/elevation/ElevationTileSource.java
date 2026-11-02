package planetdemviewer.io.elevation;

import java.io.File;
import java.io.IOException;
import planetdemviewer.model.DemTile;

/**
 * Compatibility I/O boundary for the current FABDEM implementation. File-based
 * lookup remains here while future TileSource adapters become address-based.
 */
public interface ElevationTileSource {
    void requestElevation(File tileFile);

    DemTile peekElevation(File tileFile);

    DemTile getElevationTile(File tileFile) throws IOException;
}

