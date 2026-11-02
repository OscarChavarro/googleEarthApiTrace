package planetdemviewer.gui.awt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.awt.event.MouseWheelEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.dialog.ScreenErrorThresholdPresenter;
import planetdemviewer.terrain.TerrainSimplificationCounts;
import planetdemviewer.terrain.TerrainSubdivisionScheme;

class AwtScreenErrorThresholdPanelTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private final GuiController gui = new GuiController(Configuration.GUI_DIRECTORY,
        Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);
    private AwtScreenErrorThresholdPanel panel;

    @BeforeEach
    void createPanel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel = new AwtScreenErrorThresholdPanel(new ScreenErrorThresholdPresenter(model,
                () -> new TerrainSimplificationCounts(38, 1_000L, 250L, 0L, 1)));
            panel.rebuild(gui.definitions());
        });
    }

    /** Runs after every change the model notified, which reach the panel through the event thread. */
    private static void flushEvents() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void appearsAndHidesWithTheScheme() throws Exception {
        flushEvents();
        assertFalse(panel.isVisible());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        flushEvents();
        assertTrue(panel.isVisible());
        assertEquals("τ: 1 px", panel.thresholdLabel().getText());
        assertEquals("Triangles: 1 000", panel.originalTrianglesLabel().getText());
        assertEquals("Optimized: 250 (25.0%)", panel.optimizedTrianglesLabel().getText());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.TRIANGLE_EDGE);
        flushEvents();
        assertFalse(panel.isVisible());
    }

    @Test
    void movingTheSliderChangesTheThreshold() throws Exception {
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        flushEvents();
        SwingUtilities.invokeAndWait(() -> panel.slider().setValue(0));
        flushEvents();
        assertEquals(0.0, model.getScreenErrorThresholdPixels());
        assertEquals("τ: 0 px", panel.thresholdLabel().getText());
    }

    @Test
    void theWheelMovesOneStopAndStopsAtTheEnds() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        flushEvents();
        SwingUtilities.invokeAndWait(() -> wheel(-1));
        flushEvents();
        assertEquals(2.0, model.getScreenErrorThresholdPixels());
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 20; i++) {
                wheel(1);
            }
        });
        flushEvents();
        assertEquals(0.0, model.getScreenErrorThresholdPixels());
    }

    private void wheel(int rotation) {
        panel.slider().dispatchEvent(new MouseWheelEvent(panel.slider(), MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), 0, 5, 5, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation));
    }

    @Test
    void aThresholdBetweenStopsIsNotReplacedByTheSlider() throws Exception {
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        model.setScreenErrorThresholdPixels(3.0);
        flushEvents();
        assertEquals(3.0, model.getScreenErrorThresholdPixels());
        assertEquals("τ: 3 px", panel.thresholdLabel().getText());
    }

    /** The frame is packed but never shown, so no window appears. */
    @Test
    void closingTheWindowStopsTheTimerThatWouldKeepTheApplicationAlive() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        JFrame frame = new JFrame();
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(panel.isRefreshingCounts());
            frame.add(panel);
            frame.pack();
            assertTrue(panel.isRefreshingCounts());
            frame.dispose();
            assertFalse(panel.isRefreshingCounts());
        });
    }

    @Test
    void theCheckBoxChoosesWhetherTilesShareTheirBoundaries() throws Exception {
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        flushEvents();
        assertEquals("Simplify tile boundaries", panel.dependenciesCrossTilesCheckBox().getText());
        assertTrue(panel.dependenciesCrossTilesCheckBox().isSelected(), "on by default");
        SwingUtilities.invokeAndWait(() -> panel.dependenciesCrossTilesCheckBox().doClick(0));
        flushEvents();
        assertFalse(model.isDependenciesCrossTiles());
        model.setDependenciesCrossTiles(true);
        flushEvents();
        assertTrue(panel.dependenciesCrossTilesCheckBox().isSelected());
    }

    @Test
    void neverTakesTheKeyboardFocus() {
        assertFalse(panel.slider().isFocusable());
        assertFalse(panel.dependenciesCrossTilesCheckBox().isFocusable());
    }
}
