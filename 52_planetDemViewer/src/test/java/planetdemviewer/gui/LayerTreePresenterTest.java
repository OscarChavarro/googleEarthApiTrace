package planetdemviewer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.dialog.LayerTreePresenter;
import vsdk.toolkit.gui.dialog.components.DialogTreeNode;
import vsdk.toolkit.gui.widget.Widget;
import vsdk.toolkit.gui.widget.WidgetMenuItem;

class LayerTreePresenterTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private final LayerTreePresenter presenter = new LayerTreePresenter(model);
    private final List<String> exits = new ArrayList<>();
    private final GuiController gui = new GuiController(Configuration.GUI_DIRECTORY,
        Configuration.DEFAULT_GUI_LANGUAGE, () -> exits.add("exit"), model);

    private DialogTreeNode option(int group, int option) {
        return presenter.hierarchy(gui.definitions()).getChildren().get(group).getChildren().get(option);
    }

    private static WidgetMenuItem firstItem(Widget definitions, String menu) {
        return (WidgetMenuItem) definitions.getPopup(menu).getChildren().get(0);
    }

    @Test
    void startsWithTerrainAndImageryAndWithoutPaletteImage() {
        model.setImageryAvailable(true);
        assertTrue(presenter.isSelected(option(0, 0)));
        assertFalse(presenter.isSelected(option(0, 1)));
        assertFalse(presenter.isSelected(option(1, 0)));
        assertTrue(presenter.isSelected(option(1, 1)));
    }

    @Test
    void radioOptionsChangeTheModelAndNotifyTheView() {
        model.setImageryAvailable(true);
        List<String> changes = new ArrayList<>();
        presenter.setChangeListener(() -> changes.add("changed"));

        presenter.activate(option(1, 0));
        assertFalse(model.isImageryVisible());
        presenter.activate(option(0, 1));
        assertEquals(OperationModes.PALETTE_BASED_IMAGE, model.getOperationMode());
        assertEquals(2, changes.size());
    }

    @Test
    void treeFollowsTheKeyboardActions() {
        model.setImageryAvailable(true);
        model.toggleImageryVisible();
        assertTrue(presenter.isSelected(option(1, 0)));
        model.cycleOperationMode();
        assertTrue(presenter.isSelected(option(0, 1)));
    }

    @Test
    void colorIsDisabledWithoutImageryOrOutsideTheTerrain() {
        assertFalse(presenter.isEnabled(option(1, 1)));
        assertFalse(model.isImageryVisible());
        model.toggleImageryVisible();
        assertTrue(model.isImageryEnabled());

        model.setImageryAvailable(true);
        assertTrue(presenter.isEnabled(option(1, 0)));
        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        assertFalse(presenter.isEnabled(option(1, 0)));
        presenter.activate(option(1, 0));
        assertTrue(model.isImageryEnabled());
    }

    @Test
    void languageCommandChangesTextsAndKeepsCommandsAndStructure() {
        DialogTreeNode english = presenter.hierarchy(gui.definitions());
        assertEquals("Layers", presenter.title(gui.definitions()));
        assertEquals("Exit", firstItem(gui.definitions(), "File").getName());

        gui.execute(GuiController.COMMAND_LANGUAGE_PREFIX + "SPANISH");
        Widget spanish = gui.definitions();
        assertEquals("Capas", presenter.title(spanish));
        assertEquals("Altimetría (FABDEM)", presenter.hierarchy(spanish).getChildren().get(0).getLabel());
        assertEquals(english.getChildren().size(), presenter.hierarchy(spanish).getChildren().size());
        assertEquals("Salir", firstItem(spanish, "Archivo").getName());
        assertEquals(GuiController.COMMAND_EXIT, firstItem(spanish, "Archivo").getCommandName());
    }

    @Test
    void exitCommandRunsTheExitActionAndUnknownLanguageKeepsTheCurrentOne() {
        Widget current = gui.definitions();
        gui.execute(GuiController.COMMAND_LANGUAGE_PREFIX + "KLINGON");
        assertEquals(current, gui.definitions());
        gui.execute(GuiController.COMMAND_EXIT);
        assertEquals(List.of("exit"), exits);
    }

    @Test
    void hudTextFollowsTheLanguageAndKeepsArgumentsLiteral() {
        assertEquals("View 2", model.text("IDM_HUD_VIEW_TITLE", 2));
        gui.execute(GuiController.COMMAND_LANGUAGE_PREFIX + "SPANISH");
        assertEquals("Vista 2", model.text("IDM_HUD_VIEW_TITLE", 2));
        assertEquals("FPS: {0}", model.text("IDM_HUD_FPS", "{0}"));
        assertTrue(model.text("IDM_HUD_IMAGE", 1, 2, "/data/{1}", 4, 5, 6, 7, 8).startsWith("Imagen 1/2: /data/{1} |"));
    }

    @Test
    void bothLanguagesDefineTheSameMessagesWithTheSamePlaceholders() throws Exception {
        assertEquals(messages("english.json"), messages("spanish.json"));
    }

    /** Maps each message id to the placeholders its text uses. */
    private static Map<String, Set<String>> messages(String file) throws Exception {
        Map<String, Set<String>> result = new HashMap<>();
        Matcher message = Pattern.compile("\"(IDM_[A-Z_]+)\": \"([^\"]*)\"")
            .matcher(Files.readString(Configuration.GUI_DIRECTORY.resolve(file)));
        while (message.find()) {
            Set<String> placeholders = new HashSet<>();
            Matcher placeholder = Pattern.compile("\\{\\d\\}").matcher(message.group(2));
            while (placeholder.find()) {
                placeholders.add(placeholder.group());
            }
            result.put(message.group(1), placeholders);
        }
        return result;
    }
}
