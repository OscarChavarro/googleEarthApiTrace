package dumpanalyzer;

import dumpanalyzer.config.Configuration;
import dumpanalyzer.io.TracedModelReader;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.options.CommandLineOptions;
import dumpanalyzer.processing.NeighborsProcessor;
import dumpanalyzer.render.Jogl4DumpAnalyzerRenderer;

public class Main {
    public static void main(String[] args) {
        CommandLineOptions config = CommandLineOptions.parseArgs(args);

        DumpAnalyzerModel model = new DumpAnalyzerModel();
        model.setSelectedFrameIndex(config.startFrame());
        TracedModelReader tracedModelReader = new TracedModelReader(
            Configuration.OUTPUT_ROOT,
            config.startFrame(),
            config.endFrame()
        );
        tracedModelReader.importInto(model);
        System.out.flush();
        NeighborsProcessor.preprocessNeighbors(model, model.snapshotFrames(), config.width(), config.height());

        if (!config.offline()) {
            InteractiveDebugger.start(model);
        }

        if (config.offline()) {
            model.setSelectedFrameById(config.startFrame());
            renderOffline(model, config);
        }
    }

    private static void renderOffline(DumpAnalyzerModel model, CommandLineOptions config) {
        try {
            Jogl4DumpAnalyzerRenderer renderer = new Jogl4DumpAnalyzerRenderer(model, () -> {});
            renderer.startOffscreen(config.outputPath(), config.width(), config.height());
        }
        catch (Throwable t) {
            System.out.println(
                "Warning: Offline image export is not available because there is no access to a graphics system."
            );
        }
    }
}
