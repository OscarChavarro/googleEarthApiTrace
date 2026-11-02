package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.PlanetViewerModel;

class PlanetViewerSessionTest {
    private static final long MB = 1024L * 1024L;

    @Test
    void theHudReportsWhatThePlannersKeepOnTheGpu() {
        PlanetViewerModel model = new PlanetViewerModel();
        new GuiController(Configuration.GUI_DIRECTORY, Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);
        PlanetViewerSession session = new PlanetViewerSession(model);
        TerrainFramePlanner<Object, String> terrain = session.newTerrainFramePlanner();
        try {
            assertEquals(List.of(), terrain.meshUploaded("variant", "mesh", null, 5 * MB));
            String modeLine = session.finishFrame(800, 600).get(2).text();
            assertTrue(modeLine.contains("1 (5 MB)"), modeLine);
        }
        finally {
            terrain.shutdown();
            session.shutdown();
        }
    }
}
