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
            PyramidCatalog catalog = new PyramidalImageScanner().scan(arguments.pyramidalImageFolder());
            System.out.println("Loaded pyramidal image: " + catalog.rootFolder());
            System.out.println("Tiles: " + catalog.tileCount() + ", maximum depth: " + catalog.maxDepth());
            EventQueue.invokeLater(() -> new CoverageWindow(new ViewerModel(catalog)).show());
        }
        catch (IllegalArgumentException | IOException ex) {
            System.err.println("ERROR: " + ex.getMessage());
            System.err.println(ArgumentParser.usage());
            System.exit(1);
        }
    }
}
