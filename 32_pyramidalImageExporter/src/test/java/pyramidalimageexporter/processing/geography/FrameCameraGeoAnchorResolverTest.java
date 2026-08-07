package pyramidalimageexporter.processing.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FrameCameraGeoAnchorResolverTest {
    @Test
    void mapsIbizaCameraToTheSquareGeographicQuadtree() {
        assertEquals(
            "02003303203323",
            FrameCameraGeoAnchorResolver.quadPath(1.512, 38.650, 13)
        );
    }

    @Test
    void usesTheMiddleHalfOfTheSquareForEarthLatitudes() {
        assertEquals("020", FrameCameraGeoAnchorResolver.quadPath(0.0, 0.0, 2));
        assertEquals("003", FrameCameraGeoAnchorResolver.quadPath(-180.0, -90.0, 2));
        assertEquals("022", FrameCameraGeoAnchorResolver.quadPath(180.0, 90.0, 2));
    }

    @Test
    void rejectsWeakApproximateEvidenceForCoarseLayers() {
        assertFalse(FrameCameraGeoAnchorResolver.hasSufficientExactCameraEvidence(5, 2));
        assertTrue(FrameCameraGeoAnchorResolver.hasSufficientExactCameraEvidence(5, 3));
        assertTrue(FrameCameraGeoAnchorResolver.hasSufficientExactCameraEvidence(13, 0));
    }
}
