package planetdemviewer.gui.awt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.dialog.ImageryLevelsPresenter;
import planetdemviewer.render.terrain.TerrainImageryCounts;
import planetdemviewer.render.terrain.TerrainSubdivisionScheme;

class AwtImageryLevelsPanelTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private final GuiController gui = new GuiController(Configuration.GUI_DIRECTORY,
        Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);
    private AwtImageryLevelsPanel panel;

    @BeforeEach
    void createPanel() throws Exception {
        model.setImageryAvailable(true);
        SwingUtilities.invokeAndWait(() -> {
            panel = new AwtImageryLevelsPanel(new ImageryLevelsPresenter(model,
                () -> new TerrainImageryCounts(3, 12, 12, 10, 16, 1, 2, 0)));
            panel.rebuild(gui.definitions());
        });
    }

    private static void flushEvents() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void appearsAndHidesWithTheScheme() throws Exception {
        flushEvents();
        assertFalse(panel.isVisible());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.DIAMOND_SQUARE);
        flushEvents();
        assertTrue(panel.isVisible());
        assertEquals("Color levels: 10 - 16", panel.colorLevelsLabel().getText());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.TRIANGLE_EDGE);
        flushEvents();
        assertFalse(panel.isVisible());
    }

    @Test
    void theCheckBoxChangesTheModelAndNeverTakesTheFocus() throws Exception {
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        flushEvents();
        assertTrue(panel.independentCheckBox().isSelected());
        assertFalse(panel.independentCheckBox().isFocusable());
        SwingUtilities.invokeAndWait(() -> panel.independentCheckBox().doClick(0));
        flushEvents();
        assertFalse(model.isIndependentImageryLevels());
        model.setIndependentImageryLevels(true);
        flushEvents();
        assertTrue(panel.independentCheckBox().isSelected());
    }
}
