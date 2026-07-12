package planetviewer.gui;

import java.util.function.Supplier;
import vsdk.toolkit.environment.material.RendererConfiguration;

/** Callbacks for the multi-view keys ('.', ',', 'w', 'v', 'V'), wired by InteractiveViewer to the renderer's View list. */
public record ViewActions(
    Runnable cycleSelectedView,
    Runnable cycleLayoutStyle,
    Runnable toggleCameraFrustumsVisible,
    Runnable addView,
    Runnable removeView,
    Supplier<RendererConfiguration> selectedRenderingConfiguration
) {
}
