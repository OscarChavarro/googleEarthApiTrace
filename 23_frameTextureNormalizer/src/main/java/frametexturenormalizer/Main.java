package frametexturenormalizer;

import java.nio.file.Path;
import java.util.List;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.io.FileSystemChecker;
import frametexturenormalizer.io.TraceSessionReader;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.FrameTextureNormalizerModel;
import frametexturenormalizer.options.CommandLineOptions;
import frametexturenormalizer.render.Jogl4FrameTextureNormalizerRenderer;

public class Main {
    public static void main(String[] args) {
        boolean offline = CommandLineOptions.hasArg(args, "--offline");
        boolean debugMatrix = CommandLineOptions.hasArg(args, "--debug-matrix");
        String debugFrame = CommandLineOptions.getArgValue(args, "--debug-frame=");
        if (debugMatrix) {
            System.setProperty("pib.debug.matrix", "true");
        }
        if (debugFrame != null && !debugFrame.isBlank()) {
            System.setProperty("pib.debug.matrix.frame", debugFrame.trim());
        }

        FrameTextureNormalizerModel model = new FrameTextureNormalizerModel();
        int startFrame = CommandLineOptions.startFrame(args, 0);
        int endFrame = CommandLineOptions.hasEndFrame(args)
            ? CommandLineOptions.endFrame(args, Integer.MAX_VALUE)
            : new TraceSessionReader().findLastFrameId(Path.of(Configuration.INPUT_PATH), Integer.MAX_VALUE);
        new NormalizationPipeline().run(model, startFrame, endFrame, offline);

        if (offline) {
            if (model.getSelectedFrame() == null) {
                System.out.println(
                    "Offline error: no frames available after applying range and filters. "
                        + "Check --start-frame/--end-frame and input data in "
                        + frametexturenormalizer.config.Configuration.INPUT_PATH
                );
                return;
            }
            renderOffline(
                model,
                CommandLineOptions.offlineOutputPath(args),
                CommandLineOptions.offlineWidth(args),
                CommandLineOptions.offlineHeight(args)
            );
            return;
        }

        InteractiveDebugger.runDesktopGui(model);
    }
    private static void renderOffline(FrameTextureNormalizerModel model, String outputPath, int width, int height) {
        try {
            List<FrameData> frames = model.getFrames();
            if (frames.isEmpty()) {
                System.out.println("Offline error: there are no frames to export.");
                return;
            }
            List<String> outputPaths = FileSystemChecker.buildOfflineOutputPaths(outputPath, frames.size());
            if (outputPaths.isEmpty()) {
                return;
            }
            if (frames.size() == 1) {
                Jogl4FrameTextureNormalizerRenderer renderer = new Jogl4FrameTextureNormalizerRenderer(model);
                renderer.startOffscreen(outputPaths.get(0), width, height);
                return;
            }
            for (int i = 0; i < frames.size() && i < outputPaths.size(); i++) {
                FrameData frame = frames.get(i);
                if (frame == null) {
                    continue;
                }
                if (!model.selectFrameById(frame.getId())) {
                    continue;
                }
                String frameOutput = outputPaths.get(i);
                Jogl4FrameTextureNormalizerRenderer renderer = new Jogl4FrameTextureNormalizerRenderer(model);
                renderer.startOffscreen(frameOutput, width, height);
                System.out.println("Offline sequence frame " + frame.getId() + " -> " + frameOutput);
            }
        }
        catch (Throwable t) {
            System.out.println(
                "Warning: Offline image export is not available because there is no access to a graphics system."
            );
        }
    }
}
