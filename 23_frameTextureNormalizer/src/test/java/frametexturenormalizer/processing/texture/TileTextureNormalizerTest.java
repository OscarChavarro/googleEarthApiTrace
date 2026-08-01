package frametexturenormalizer.processing.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.processing.uncles.ToUncleRelationship;
import frametexturenormalizer.processing.uncles.UncleDirections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TileTextureNormalizerTest {
    @Test
    void canonicalizesTexturePathsWithoutChangingNativeIdentityOrRelationships() {
        String firstTexture = "/capture/00758/10.png";
        String secondTexture = "/capture/00758/20.png";
        String canonicalTexture = "/capture/00347/523.png";
        ToUncleRelationship uncle = new ToUncleRelationship(UncleDirections.EAST_SOUTH, "00758_30", null);
        TileInstance first = tile(10, firstTexture, null, 20, List.of(uncle));
        TileInstance second = tile(20, secondTexture, 10, null, List.of());
        FrameData frame = new FrameData(758, List.of(first, second), null, null, null, null, false);

        FrameData normalized = TileTextureNormalizer.normalizeFrame(
            frame,
            Map.of(firstTexture, canonicalTexture, secondTexture, canonicalTexture)
        );

        assertEquals(List.of(10, 20), normalized.getTiles().stream().map(TileInstance::getTileId).toList());
        assertEquals("00758_10", normalized.getTiles().get(0).getScopedId());
        assertEquals("00758_20", normalized.getTiles().get(1).getScopedId());
        assertNull(normalized.getTiles().get(0).getSouthNeighbor());
        assertEquals(20, normalized.getTiles().get(0).getNorthNeighbor());
        assertEquals(10, normalized.getTiles().get(1).getSouthNeighbor());
        assertNull(normalized.getTiles().get(1).getNorthNeighbor());
        assertEquals(List.of(uncle), normalized.getTiles().get(0).getUncles());
        assertEquals(canonicalTexture, normalized.getTiles().get(0).getTextureFile());
        assertEquals(canonicalTexture, normalized.getTiles().get(1).getTextureFile());
    }

    private static TileInstance tile(
        int id,
        String texture,
        Integer south,
        Integer north,
        List<ToUncleRelationship> uncles
    ) {
        return new TileInstance(id, 758, texture, south, north, null, null, null, null, null, null, false, uncles);
    }
}
