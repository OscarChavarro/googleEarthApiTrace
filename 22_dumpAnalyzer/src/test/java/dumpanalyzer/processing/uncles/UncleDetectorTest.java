package dumpanalyzer.processing.uncles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.List;
import org.junit.jupiter.api.Test;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

final class UncleDetectorTest {
    @Test
    void detectsAFineTileAgainstAReferenceTwoLevelsAbove() {
        TileInstance fine = tile(
            "40_1",
            quadStrip(0.0, 0.0, 1.0, 1.0),
            quadStrip(0.0, 0.0, 1.0, 1.0),
            false
        );
        TileInstance reference = tile(
            "40_2",
            quadStrip(1.0, 0.0, 2.0, 1.0),
            quadStrip(0.0, 0.25, 0.25, 0.5),
            true
        );
        Frame frame = new Frame(40, List.of(fine, reference), List.of(), null, null, null);
        UncleDetector detector = new UncleDetector();

        List<ToUncleRelationship> relationships = detector.detect(
            frame,
            fine,
            detector.prepareCandidates(frame)
        );

        assertEquals(1, relationships.size());
        ToUncleRelationship relationship = relationships.get(0);
        assertEquals("40_2", relationship.referenceContentId());
        assertEquals(2, relationship.levelDelta());
        assertEquals(2, relationship.rowOffset());
        assertEquals(-1, relationship.columnOffset());
        assertEquals(UncleRelationshipKind.ADJACENT_BORDER, relationship.relationshipKind());
        assertEquals(1, reference.getRelationshipGeometries().size());
    }

    private static TileInstance tile(
        String id,
        List<Vector3Dd> geometry,
        List<Vector3Dd> uv,
        boolean skipped
    ) {
        return new TileInstance(
            id,
            "/tmp/" + id + ".png",
            null,
            null,
            null,
            null,
            null,
            null,
            geometry,
            List.of(geometry),
            List.of(uv),
            "GL_TRIANGLE_STRIP",
            0,
            0,
            geometry.size(),
            geometry.size(),
            skipped,
            skipped ? "multiple strips" : "",
            null,
            null
        );
    }

    private static List<Vector3Dd> quadStrip(double minX, double minY, double maxX, double maxY) {
        double midX = (minX + maxX) / 2.0;
        double midY = (minY + maxY) / 2.0;
        List<Vector3Dd> unique = List.of(
            new Vector3Dd(midX, midY, 0.0),
            new Vector3Dd(minX, minY, 0.0),
            new Vector3Dd(midX, minY, 0.0),
            new Vector3Dd(maxX, minY, 0.0),
            new Vector3Dd(maxX, midY, 0.0),
            new Vector3Dd(maxX, maxY, 0.0),
            new Vector3Dd(midX, maxY, 0.0),
            new Vector3Dd(minX, maxY, 0.0),
            new Vector3Dd(minX, midY, 0.0)
        );
        java.util.ArrayList<Vector3Dd> strip = new java.util.ArrayList<>(20);
        strip.addAll(unique);
        while (strip.size() < 20) {
            strip.add(unique.get(0));
        }
        return List.copyOf(strip);
    }
}
