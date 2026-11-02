package planetdemviewer.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.jogl4.Jogl4PlanetViewerRenderer;

class ApplicationStateStoreTest {
    @TempDir
    Path directory;

    @Test
    void savesAndRestoresTheImageryChoice() throws Exception {
        Path file = directory.resolve("state.json");
        PlanetViewerModel saved = new PlanetViewerModel();
        saved.setImageryEnabled(false);
        ApplicationStateStore.save(file, saved, new Jogl4PlanetViewerRenderer(saved));

        PlanetViewerModel restored = new PlanetViewerModel();
        assertTrue(ApplicationStateStore.restoreModel(file, restored).isPresent());
        assertFalse(restored.isImageryEnabled());
    }

    @Test
    void stateFilesWithoutTheImageryChoiceKeepItOn() throws Exception {
        Path file = directory.resolve("state.json");
        PlanetViewerModel saved = new PlanetViewerModel();
        ApplicationStateStore.save(file, saved, new Jogl4PlanetViewerRenderer(saved));
        Files.writeString(file, Files.readString(file).replace(", \"imageryEnabled\": true", ""));
        assertFalse(Files.readString(file).contains("imageryEnabled"));

        PlanetViewerModel restored = new PlanetViewerModel();
        assertTrue(ApplicationStateStore.restoreModel(file, restored).isPresent());
        assertTrue(restored.isImageryEnabled());
    }
}
