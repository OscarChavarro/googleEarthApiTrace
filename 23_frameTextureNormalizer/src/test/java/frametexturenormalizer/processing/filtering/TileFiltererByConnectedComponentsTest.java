package frametexturenormalizer.processing.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import frametexturenormalizer.model.TileInstance;

final class TileFiltererByConnectedComponentsTest {
    @Test
    void partitionsAllReciprocalComponentsAndDropsSingletons() {
        TileFiltererByConnectedComponents filterer = new TileFiltererByConnectedComponents();
        List<TileInstance> tiles = List.of(
            tile(10, null, null, 11, null),
            tile(11, null, null, null, 10),
            tile(30, 31, null, null, null),
            tile(31, null, 30, null, null),
            tile(99, null, null, null, null)
        );

        List<List<TileInstance>> components = filterer.partitionReciprocalComponents(tiles);

        assertEquals(2, components.size());
        assertEquals(List.of(10, 11), components.get(0).stream().map(TileInstance::getTileId).toList());
        assertEquals(List.of(30, 31), components.get(1).stream().map(TileInstance::getTileId).toList());
        assertEquals(List.of(10, 11, 30, 31), filterer.filter(tiles).stream().map(TileInstance::getTileId).toList());
    }

    private static TileInstance tile(int id, Integer south, Integer north, Integer east, Integer west) {
        return new TileInstance(id, 1, "/tmp/" + id + ".png", south, north, east, west, null, null);
    }
}
