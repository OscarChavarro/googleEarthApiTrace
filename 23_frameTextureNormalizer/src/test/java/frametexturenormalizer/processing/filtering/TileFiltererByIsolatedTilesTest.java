package frametexturenormalizer.processing.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

final class TileFiltererByIsolatedTilesTest {
    @Test
    void terminatesWhenTileIdsAreDuplicated() {
        TileFiltererByIsolatedTiles filterer = new TileFiltererByIsolatedTiles();
        FrameData frame = frame(
            tile(10, null, null, 11, null),
            tile(10, null, null, 11, null),
            tile(11, null, null, null, 10)
        );

        FrameData filtered = assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> filterer.removeIsolatedTiles(frame)
        );

        assertEquals(List.of(10, 10, 11), filtered.getTiles().stream().map(TileInstance::getTileId).toList());
    }

    private static FrameData frame(TileInstance... tiles) {
        return new FrameData(1, List.of(tiles), List.of(), null, null, null, false);
    }

    private static TileInstance tile(int id, Integer south, Integer north, Integer east, Integer west) {
        return new TileInstance(id, 1, "/tmp/" + id + ".png", south, north, east, west, null, null);
    }
}
