package planetdemviewer.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import planetdemviewer.render.terrain.TerrainSubdivisionScheme;

class ViewerMcpServerTest {
    @Test
    void theToolListOffersEverySubdivisionScheme() {
        assertTrue(ViewerMcpServer.TOOLS.contains("\"name\":\"set_subdivision_scheme\""));
        assertTrue(ViewerMcpServer.TOOLS.contains("\"name\":\"set_independent_imagery_levels\""));
        for (TerrainSubdivisionScheme scheme : TerrainSubdivisionScheme.values()) {
            assertTrue(ViewerMcpServer.TOOLS.contains("\"" + scheme.name() + "\""), scheme.name());
            assertEquals(scheme, ViewerMcpServer.subdivisionScheme(scheme.name()));
        }
    }

    @Test
    void readsNumbersInEveryNotation() {
        String request = "{\"position_x\":-130.5,\"position_y\":1e3,\"position_z\":6.1E-2,\"focus_x\": 4}";
        assertEquals(-130.5, ViewerMcpServer.doubleValue(request, "position_x"));
        assertEquals(1000.0, ViewerMcpServer.doubleValue(request, "position_y"));
        assertEquals(0.061, ViewerMcpServer.doubleValue(request, "position_z"));
        assertEquals(4.0, ViewerMcpServer.doubleValue(request, "focus_x"));
        assertThrows(IllegalArgumentException.class, () -> ViewerMcpServer.doubleValue(request, "up_x"));
    }

    @Test
    void anUnknownSchemeNamesTheValidOnes() {
        String message = assertThrows(IllegalArgumentException.class,
            () -> ViewerMcpServer.subdivisionScheme("diamond")).getMessage();
        assertTrue(message.contains("diamond"), message);
        assertTrue(message.contains("TRIANGLE_EDGE"), message);
        assertTrue(message.contains("DIAMOND_SQUARE"), message);
    }
}
