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

    @Test
    void readsGenericAncestorOffsetsAndCompactReferenceGeometry() throws Exception {
        List<TileInstance> tiles = new FrameTileJsonReader().read(JSON.readTree("""
            {
              "id": 40,
              "tiles": [{
                "contentId": "40_1",
                "textureFile": "/tmp/reference.png",
                "relationshipGeometries": [{
                  "vertexCount": 4,
                  "vertices": [
                    {"x":0,"y":0,"z":0,"u":0.25,"v":0.25},
                    {"x":0,"y":1,"z":0,"u":0.25,"v":0.5},
                    {"x":1,"y":0,"z":0,"u":0.5,"v":0.25},
                    {"x":1,"y":1,"z":0,"u":0.5,"v":0.5}
                  ]
                }],
                "uncles": [{
                  "referenceContentId": "30_1",
                  "relationshipKind": "ADJACENT_BORDER",
                  "levelDelta": 2,
                  "rowOffset": 2,
                  "columnOffset": 4
                }]
              }]
            }
            """));

        assertEquals(1, tiles.get(0).getRelationshipGeometries().size());
        assertEquals("00030_1", tiles.get(0).getUncles().get(0).referenceContentId());
        assertEquals(2, tiles.get(0).getUncles().get(0).levelDelta());
        assertEquals(4, tiles.get(0).getUncles().get(0).columnOffset());
    }
}
