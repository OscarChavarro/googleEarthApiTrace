package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.terrain.TerrainFramePlanner;

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
            // Every line is written twice, for its shadow.
            String modeLine = session.finishFrame(800, 600).get(2 * 2).text();
            assertTrue(modeLine.contains("1 (5 MB)"), modeLine);
        }
        finally {
            terrain.shutdown();
            session.shutdown();
        }
    }

    @Test
    void tileBoundsAreTheBoundingVolumesOfTheSelectedView() {
        PlanetViewerModel model = new PlanetViewerModel();
        new GuiController(Configuration.GUI_DIRECTORY, Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);
        PlanetViewerSession session = new PlanetViewerSession(model);
        try {
            session.toggleTerrainTileBoundsVisible();
            assertTrue(session.getSelectedViewRenderingConfiguration().isBoundingVolumeSet());
            session.getSelectedViewRenderingConfiguration().changeBoundingVolume();
            assertFalse(session.isTerrainTileBoundsVisible());
            session.setTerrainTileBoundsVisible(true);
            assertTrue(session.getSelectedViewRenderingConfiguration().isBoundingVolumeSet());
        }
        finally {
            session.shutdown();
        }
    }
}
