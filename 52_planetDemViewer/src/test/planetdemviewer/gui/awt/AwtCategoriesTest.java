package planetdemviewer.gui.awt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.CameraControlMode;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.dialog.CameraControlPresenter;

class AwtCategoriesTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private final GuiController gui = new GuiController(Configuration.GUI_DIRECTORY,
        Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);

    private static void pressHeader(AwtCollapsiblePanel panel) {
        JLabel header = panel.header();
        header.dispatchEvent(new MouseEvent(header, MouseEvent.MOUSE_PRESSED, 0L, MouseEvent.BUTTON1_DOWN_MASK,
            1, 1, 1, false, MouseEvent.BUTTON1));
    }

    @Test
    void theCategoriesTakeOnlyTheHeightOfTheirContent() {
        JLabel first = new JLabel("first");
        AwtCollapsiblePanel collapsible = new AwtCollapsiblePanel(first);
        AwtControlCategoriesPanel categories = new AwtControlCategoriesPanel();
        categories.addCategory(collapsible);
        int expanded = categories.getPreferredSize().height;
        collapsible.setExpanded(false);
        // In a window, the revalidation reaches this panel; outside of one, nothing was ever valid.
        categories.invalidate();
        assertTrue(categories.getPreferredSize().height < expanded);
        assertTrue(categories.getScrollableTracksViewportWidth());
        assertFalse(categories.getScrollableTracksViewportHeight());
    }

    @Test
    void theHeaderCollapsesAndExpandsTheContent() {
        JLabel content = new JLabel("content");
        AwtCollapsiblePanel panel = new AwtCollapsiblePanel(content);
        assertTrue(panel.isExpanded());
        pressHeader(panel);
        assertFalse(content.isVisible());
        pressHeader(panel);
        assertTrue(content.isVisible());
    }

    @Test
    void theSelectFollowsTheModelAndChangesIt() throws Exception {
        AwtCameraControlPanel[] panel = new AwtCameraControlPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new AwtCameraControlPanel(new CameraControlPresenter(model));
            panel[0].rebuild(gui.definitions());
        });
        assertEquals(2, panel[0].select().getItemCount());
        assertEquals("Aquynza", panel[0].select().getSelectedItem());
        assertFalse(panel[0].select().isFocusable());

        SwingUtilities.invokeAndWait(() -> panel[0].select().setSelectedIndex(1));
        assertEquals(CameraControlMode.LEVELS_BROWSER, model.getCameraControlMode());

        model.setCameraControlMode(CameraControlMode.AQUYNZA);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, panel[0].select().getSelectedIndex());
    }

    @Test
    void theLanguageChangesTheOptionsWithoutChangingTheMode() throws Exception {
        AwtCameraControlPanel[] panel = new AwtCameraControlPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new AwtCameraControlPanel(new CameraControlPresenter(model));
            panel[0].rebuild(gui.definitions());
            panel[0].select().setSelectedIndex(1);
            gui.execute(GuiController.COMMAND_LANGUAGE_PREFIX + "SPANISH");
            panel[0].rebuild(gui.definitions());
        });
        assertEquals("Navegador de niveles", panel[0].select().getSelectedItem());
        assertEquals(CameraControlMode.LEVELS_BROWSER, model.getCameraControlMode());
    }
}
