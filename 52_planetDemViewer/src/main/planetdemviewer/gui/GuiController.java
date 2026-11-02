package planetdemviewer.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import planetdemviewer.logger.Logger;
import planetdemviewer.model.PlanetViewerModel;
import vsdk.toolkit.gui.widget.Widget;
import vsdk.toolkit.io.gui.DialogPersistence;
import vsdk.toolkit.io.gui.GuiPersistence;

/** Loads the Vitral GUI definition of each language and runs menu commands; no Swing types. */
public final class GuiController {
    public static final String COMMAND_EXIT = "IDC_FILE_QUIT";
    public static final String COMMAND_LANGUAGE_PREFIX = "IDC_CUSTOMIZE_LANGUAGE_";

    private final Path directory;
    private final Runnable exitAction;
    private final PlanetViewerModel model;
    private Runnable definitionsListener = () -> { };
    private Widget definitions;

    /** @throws IllegalStateException if the initial language can not be loaded */
    public GuiController(Path directory, String language, Runnable exitAction, PlanetViewerModel model) {
        this.directory = directory;
        this.exitAction = exitAction;
        this.model = model;
        try {
            definitions = load(language);
            publishToModel();
        }
        catch (Exception e) {
            throw new IllegalStateException("Can not read the GUI definition for " + language + " from "
                + directory.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    public Widget definitions() {
        return definitions;
    }

    public void setDefinitionsListener(Runnable definitionsListener) {
        this.definitionsListener = definitionsListener;
    }

    public void execute(String command) {
        if (COMMAND_EXIT.equals(command)) {
            exitAction.run();
        }
        else if (command.startsWith(COMMAND_LANGUAGE_PREFIX)) {
            setLanguage(command.substring(COMMAND_LANGUAGE_PREFIX.length()).toLowerCase(Locale.ROOT));
        }
    }

    private void setLanguage(String language) {
        try {
            definitions = load(language);
            publishToModel();
            definitionsListener.run();
        }
        catch (Exception e) {
            Logger.error("Could not change the GUI language to " + language + ": " + e.getMessage());
        }
    }

    private void publishToModel() {
        model.writeLocked(() -> model.setGuiDefinitions(definitions));
    }

    private Widget load(String language) throws Exception {
        Widget loaded;
        try (InputStream source = open(language + ".json")) {
            // The second argument only locates command icons, and these definitions have none.
            loaded = GuiPersistence.importAquynzaGui(source, directory.toString());
        }
        try (InputStream dialogs = open("dialogs.json")) {
            DialogPersistence.importDialogs(dialogs, loaded);
        }
        return loaded;
    }

    private InputStream open(String file) throws IOException {
        return Files.newInputStream(directory.resolve(file));
    }
}
