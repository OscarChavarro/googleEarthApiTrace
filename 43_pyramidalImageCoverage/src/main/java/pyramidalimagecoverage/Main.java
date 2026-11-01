package pyramidalimagecoverage;

import java.awt.EventQueue;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import pyramidalimagecoverage.gui.CoverageWindow;
import pyramidalimagecoverage.io.PyramidalImageScanner;
import pyramidalimagecoverage.io.TileImageRepository;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.options.ArgumentParser;
import pyramidalimagecoverage.options.CliArguments;

public final class Main {
    private static final long SELECTED_LEVEL_REFRESH_MINUTES = 10;
    private Main() {
    }

    public static void main(String[] args) {
        System.setProperty("sun.java2d.uiScale", "1.0");
        try {
            CliArguments arguments = ArgumentParser.parse(args);
            PyramidalImageScanner scanner = new PyramidalImageScanner();
            PyramidCatalog catalog = scanner.scanRoot(arguments.pyramidalImageFolder());
            ViewerModel model = new ViewerModel(catalog);
            model.showPlannedGroupsAtStartup();
            String selectedQuadkey = System.getProperty("pyramidalimagecoverage.selectedQuadkey", "").trim();
            if (!selectedQuadkey.isEmpty()) {
                model.toggleSelection(TileAddress.fromQuadKey(selectedQuadkey));
            }
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
            var selectedLevelRefresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "selected-level-pyramid-refresher");
                thread.setDaemon(true);
                return thread;
            });
            TileImageRepository images = TileImageRepository.asynchronous();
            Runnable refreshCoverage = () -> {
                int selectedDepth = model.selectedDepth();
                try {
                    System.out.println("Refreshing coverage at depth " + selectedDepth + "...");
                    scanner.scanLevel(catalog, selectedDepth, () -> { });
                    EventQueue.invokeLater(() -> {
                        images.invalidateAll();
                        model.catalogChanged();
                        System.out.println("Coverage refreshed at depth " + selectedDepth
                            + "; decoded images cleared and groups recalculated.");
                    });
                }
                catch (IOException ex) {
                    System.err.println("WARNING: Could not refresh level " + selectedDepth + ": " + ex.getMessage());
                }
            };
            EventQueue.invokeLater(() -> {
                new CoverageWindow(model, images,
                    () -> selectedLevelRefresher.execute(refreshCoverage)).show();
                // Give the first paint (and its level-0 image request) priority over tree indexing.
                EventQueue.invokeLater(scannerThread::start);
            });
            selectedLevelRefresher.scheduleWithFixedDelay(refreshCoverage,
                SELECTED_LEVEL_REFRESH_MINUTES, SELECTED_LEVEL_REFRESH_MINUTES, TimeUnit.MINUTES);
        }
        catch (IllegalArgumentException | IOException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.err.println(ArgumentParser.usage());
            System.exit(1);
        }
    }
}
