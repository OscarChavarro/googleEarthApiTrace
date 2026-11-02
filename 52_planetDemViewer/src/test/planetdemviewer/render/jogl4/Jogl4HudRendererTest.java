package planetdemviewer.render.jogl4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.HudFrameStats;
import planetdemviewer.render.ViewportLayout;
import planetdemviewer.terrain.TerrainSubdivisionScheme;

class Jogl4HudRendererTest {
    private static final long MB = 1024L * 1024L;

    private final PlanetViewerModel model = new PlanetViewerModel();
    private final GuiController gui = new GuiController(
        Configuration.GUI_DIRECTORY, Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);
    private final ViewportLayout viewports = new ViewportLayout(model.getViewingCamera());
    private final Jogl4HudRenderer hud = new Jogl4HudRenderer(model, viewports);

    private List<String> lines(boolean uniformLod) {
        return hud.statusLines(new HudFrameStats(7, 3 * MB, 11, 5 * MB, 0L, 0, uniformLod));
    }

    @Test
    void writesOneLineForEachHudSection() {
        assertEquals(5, lines(false).size());
    }

    @Test
    void reportsTheStatisticsOfTheFrame() {
        String camera = lines(false).get(1);
        assertTrue(camera.contains("7 (3 MB)"), camera);
        String mode = lines(false).get(2);
        assertTrue(mode.contains("11 (5 MB)"), mode);
    }

    @Test
    void followsTheModelState() {
        String triangles = lines(false).get(2);
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.DIAMOND_SQUARE);
        String diamonds = lines(true).get(2);
        assertTrue(triangles.contains(model.text("IDM_LAYER_SUBDIVISION_TRIANGLE_EDGE")), triangles);
        assertTrue(triangles.contains(model.text("IDM_HUD_OFF")), triangles);
        assertTrue(diamonds.contains(model.text("IDM_LAYER_SUBDIVISION_DIAMOND_SQUARE")), diamonds);
        assertTrue(diamonds.contains(model.text("IDM_HUD_ON")), diamonds);

        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        assertTrue(lines(false).get(2).contains(model.text("IDM_LAYER_ELEVATION_PALETTE")));
    }

    @Test
    void namesTheSelectedViewAndTheLayout() {
        viewports.addView();
        String camera = lines(false).get(1);
        assertTrue(camera.contains(model.text("IDM_HUD_VIEW_TITLE", 2)), camera);
        assertEquals(model.text("IDM_HUD_VIEW_TITLE", 1),
            Jogl4HudRenderer.viewTitle(model, viewports, viewports.mainView()));
    }

    @Test
    void addsTheTotalGpuMemoryOfBothRenderers() {
        assertEquals(8 * MB, new HudFrameStats(0, 3 * MB, 0, 5 * MB, 0L, 0, false).totalGpuBytes());
    }

    @Test
    void formatsBytesInTheLargestFittingUnit() {
        assertEquals("0 B", Jogl4HudRenderer.formatBytes(0));
        assertEquals("1023 B", Jogl4HudRenderer.formatBytes(1023));
        assertEquals("1.0 KiB", Jogl4HudRenderer.formatBytes(1024));
        assertEquals("1.5 MiB", Jogl4HudRenderer.formatBytes(MB + MB / 2));
        assertEquals("2048.0 TiB", Jogl4HudRenderer.formatBytes(2048L * 1024L * 1024L * 1024L * 1024L));
    }
}
