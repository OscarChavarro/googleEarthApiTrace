package pyramidalimagecoverage;

import java.awt.EventQueue;
import java.io.IOException;
import pyramidalimagecoverage.gui.CoverageWindow;
import pyramidalimagecoverage.io.PyramidalImageScanner;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.options.ArgumentParser;
import pyramidalimagecoverage.options.CliArguments;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        try {
            CliArguments arguments = ArgumentParser.parse(args);
            PyramidalImageScanner scanner = new PyramidalImageScanner();
            PyramidCatalog catalog = scanner.scanRoot(arguments.pyramidalImageFolder());
            ViewerModel model = new ViewerModel(catalog);
            System.out.println("Loaded pyramidal image: " + catalog.rootFolder());
            System.out.println("Root tile ready; indexing remaining tiles in background...");
            Thread scannerThread = new Thread(() -> {
                try {
                    scanner.scanRemaining(
                        catalog, () -> EventQueue.invokeLater(model::catalogChanged)
                    );
                    System.out.println(
                        "Indexed tiles: " + catalog.tileCount()
                            + ", maximum depth: " + catalog.maxDepth()
                    );
                }
                catch (IOException ex) {
                    System.err.println("WARNING: Background tile indexing stopped: " + ex.getMessage());
                }
            }, "pyramid-catalog-loader");
            scannerThread.setDaemon(true);
            EventQueue.invokeLater(() -> {
                new CoverageWindow(model).show();
                // Give the first paint (and its level-0 image request) priority over tree indexing.
                EventQueue.invokeLater(scannerThread::start);
            });
        }
        catch (IllegalArgumentException | IOException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.err.println(ArgumentParser.usage());
            System.exit(1);
        }
    }
}
