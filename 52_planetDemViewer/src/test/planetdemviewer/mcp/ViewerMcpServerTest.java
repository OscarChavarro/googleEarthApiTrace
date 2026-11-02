package planetdemviewer.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import planetdemviewer.terrain.TerrainSubdivisionScheme;

class ViewerMcpServerTest {
    @Test
    void theToolListOffersEverySubdivisionScheme() {
        assertTrue(ViewerMcpServer.TOOLS.contains("\"name\":\"set_subdivision_scheme\""));
        for (TerrainSubdivisionScheme scheme : TerrainSubdivisionScheme.values()) {
            assertTrue(ViewerMcpServer.TOOLS.contains("\"" + scheme.name() + "\""), scheme.name());
            assertEquals(scheme, ViewerMcpServer.subdivisionScheme(scheme.name()));
        }
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
