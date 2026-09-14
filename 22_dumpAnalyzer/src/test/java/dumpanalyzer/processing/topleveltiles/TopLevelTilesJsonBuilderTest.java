package dumpanalyzer.processing.topleveltiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

final class TopLevelTilesJsonBuilderTest {
    @Test
    void combinesGlobeLevelMeshSplitAcrossTextureAtlases() {
        Frame frame = new Frame(
            12,
            List.of(tile("atlas-a", 72), tile("atlas-b", 248), tile("other", 10)),
            List.of(),
            null,
            null,
            null
        );

        List<TileInstance.TriangleStripGeometry> selected =
            TopLevelTilesJsonBuilder.selectGlobeLevelGeometries(frame);

        assertEquals(320, selected.size());
    }

    private static TileInstance tile(String id, int stripCount) {
        List<List<Vector3Dd>> strips = new ArrayList<>(stripCount);
        List<List<Vector3Dd>> textureCoordinates = new ArrayList<>(stripCount);
        for (int i = 0; i < stripCount; i++) {
            strips.add(canonicalStrip(i));
            textureCoordinates.add(canonicalStrip(i));
        }
        return new TileInstance(
            id,
            "/tmp/" + id + ".png",
            null, null, null, null, null, null,
            List.of(),
            strips,
            textureCoordinates,
            "GL_TRIANGLE_STRIP",
            0, 0, 0, 0,
            true,
            "multiple strips",
            null,
            null
        );
    }

    private static List<Vector3Dd> canonicalStrip(int offset) {
        double x = offset * 2.0;
        List<Vector3Dd> unique = List.of(
            new Vector3Dd(x + 0.5, 0.5, 0.0),
            new Vector3Dd(x, 0.0, 0.0),
            new Vector3Dd(x + 0.5, 0.0, 0.0),
            new Vector3Dd(x + 1.0, 0.0, 0.0),
            new Vector3Dd(x + 1.0, 0.5, 0.0),
            new Vector3Dd(x + 1.0, 1.0, 0.0),
            new Vector3Dd(x + 0.5, 1.0, 0.0),
            new Vector3Dd(x, 1.0, 0.0),
            new Vector3Dd(x, 0.5, 0.0)
        );
        ArrayList<Vector3Dd> strip = new ArrayList<>(20);
        strip.addAll(unique);
        while (strip.size() < 20) {
            strip.add(unique.get(0));
        }
        return List.copyOf(strip);
    }
}
