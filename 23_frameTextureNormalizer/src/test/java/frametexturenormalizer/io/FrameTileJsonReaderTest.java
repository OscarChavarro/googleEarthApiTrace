package frametexturenormalizer.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.processing.uncles.UncleRelationshipKind;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FrameTileJsonReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void preservesExplicitRelationshipKindAndKeepsLegacyKindUnknown() throws Exception {
        List<TileInstance> tiles = new FrameTileJsonReader().read(JSON.readTree("""
            {
              "id": 40,
              "tiles": [{
                "contentId": "40_1",
                "textureFile": "/tmp/1.png",
                "uncles": [
                  {
                    "direction": "EAST_SOUTH",
                    "uncleContentId": "30_1",
                    "relationshipKind": "ADJACENT_BORDER"
                  },
                  {
                    "direction": "WEST_NORTH",
                    "uncleContentId": "30_2"
                  }
                ]
              }]
            }
            """));

        assertEquals(1, tiles.size());
        assertEquals(
            UncleRelationshipKind.ADJACENT_BORDER,
            tiles.get(0).getUncles().get(0).relationshipKind()
        );
        assertNull(tiles.get(0).getUncles().get(1).relationshipKind());
    }
}
