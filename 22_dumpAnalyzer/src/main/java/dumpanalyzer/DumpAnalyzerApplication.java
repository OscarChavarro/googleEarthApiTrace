package dumpanalyzer;

import dumpanalyzer.config.Configuration;
import dumpanalyzer.io.FrameJsonWriter;
import dumpanalyzer.io.TraceSessionFrameImporter;
import dumpanalyzer.model.state.DumpAnalyzerState;
import dumpanalyzer.options.CommandLineOptions;
import dumpanalyzer.processing.NeighborsProcessor;
import dumpanalyzer.processing.topleveltiles.TopLevelTilesJsonBuilder;
import dumpanalyzer.render.Jogl4DumpAnalyzerRenderer;

public final class DumpAnalyzerApplication {
    public void run(String[] args) {
        CommandLineOptions config = CommandLineOptions.parseArgs(args);

        DumpAnalyzerState model = new DumpAnalyzerState();
        model.setSelectedFrameIndex(config.startFrame());
        TraceSessionFrameImporter traceSessionFrameImporter = new TraceSessionFrameImporter(
            Configuration.OUTPUT_ROOT,
            config.startFrame(),
            config.endFrame()
        );
        traceSessionFrameImporter.importInto(model);
        System.out.flush();
        NeighborsProcessor.preprocessNeighbors(model, model.snapshotFrames(), config.width(), config.height());
        TopLevelTilesJsonBuilder.preprocessGlobeLevelTileSets(model.snapshotFrames());
        FrameJsonWriter.writeFramesParallelWithProgress(Configuration.OUTPUT_ROOT, model.snapshotFrames());

        if (!config.offline()) {
            InteractiveDebugger.start(model);
            return;
        }

        model.setSelectedFrameById(config.startFrame());
        if (config.tileContentId() != null && !config.tileContentId().isBlank()) {
            model.setSelectedTileByContentId(config.tileContentId());
            model.getRendererConfiguration().setBoundingVolume(true);
            model.getRendererConfiguration().setWires(true);
        }
        renderOffline(model, config);
    }

    private static void renderOffline(DumpAnalyzerState model, CommandLineOptions config) {
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
