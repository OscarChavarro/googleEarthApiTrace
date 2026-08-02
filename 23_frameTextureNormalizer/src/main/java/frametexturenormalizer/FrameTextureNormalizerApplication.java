package frametexturenormalizer;

import java.nio.file.Path;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.io.FrameJsonSessionReader;
import frametexturenormalizer.model.state.FrameTextureNormalizerState;
import frametexturenormalizer.options.CommandLineOptions;

public final class FrameTextureNormalizerApplication {
    public void run(String[] args) {
        boolean offline = CommandLineOptions.hasArg(args, "--offline");
        boolean debugMatrix = CommandLineOptions.hasArg(args, "--debug-matrix");
        String debugFrame = CommandLineOptions.getArgValue(args, "--debug-frame=");
        if (debugMatrix) {
            System.setProperty("pib.debug.matrix", "true");
        }
        if (debugFrame != null && !debugFrame.isBlank()) {
            System.setProperty("pib.debug.matrix.frame", debugFrame.trim());
        }

        FrameTextureNormalizerState model = new FrameTextureNormalizerState();
        int startFrame = CommandLineOptions.startFrame(args, 0);
        int endFrame = CommandLineOptions.hasEndFrame(args)
            ? CommandLineOptions.endFrame(args, Integer.MAX_VALUE)
            : new FrameJsonSessionReader().findLastFrameId(Path.of(Configuration.INPUT_PATH), Integer.MAX_VALUE);
        new FrameNormalizationPipeline().run(model, startFrame, endFrame, offline);

        if (offline) {
            return;
        }
        InteractiveDebugger.runDesktopGui(model);
    }
}
