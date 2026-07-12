package planetviewer.gui;

import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import planetviewer.config.Configuration;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.logger.Logger;
import planetviewer.model.PlanetViewerModel;
import planetviewer.model.PyramidalImage;

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
            chooser.setDialogTitle("Load pyramidal image folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int result = chooser.showOpenDialog(parent);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File selected = chooser.getSelectedFile();
            Optional<PyramidalImage> loaded = new PyramidalImageFolderReader().read(Path.of(selected.getAbsolutePath()));
            if (loaded.isEmpty()) {
                Logger.error("Not a valid pyramidal image folder (missing 0.png): " + selected);
                return;
            }
            model.addImage(loaded.get());
            Logger.info(
                "loaded pyramidal image " + loaded.get().getSourceFolder()
                    + ": " + loaded.get().getTileCount() + " tiles, height " + loaded.get().getHeight()
            );
            if (repaintAction != null) {
                repaintAction.run();
            }
        });
    }
}
