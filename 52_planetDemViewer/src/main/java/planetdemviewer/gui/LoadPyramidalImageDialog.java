package planetdemviewer.gui;

import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import planetdemviewer.config.Configuration;
import planetdemviewer.io.PyramidalImageFolderReader;
import planetdemviewer.logger.Logger;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImage;

/**
 * Wraps a directories-only JFileChooser to let the user add a pyramidal
 * image folder to the model's stack at runtime.
 */
public final class LoadPyramidalImageDialog {
    private LoadPyramidalImageDialog() {
    }

    public static void showAndLoad(Component parent, PlanetViewerModel model, Runnable repaintAction) {
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser(new File(Configuration.defaultDatasetDirectory()));
            chooser.setDialogTitle("Load pyramidal DEM folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showOpenDialog(parent);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File selected = chooser.getSelectedFile();
            Optional<PyramidalImage> loaded = new PyramidalImageFolderReader().read(Path.of(selected.getAbsolutePath()));
            if (loaded.isEmpty()) {
                Logger.error("Not a valid DEM pyramid (missing or invalid 0.bin): " + selected);
                return;
            }
            model.addImage(loaded.get());
            Logger.info("opened DEM pyramid " + loaded.get().getSourceFolder()
                + ": root tile ready; lower levels will be indexed lazily");
            if (repaintAction != null) {
                repaintAction.run();
            }
        });
    }
}
