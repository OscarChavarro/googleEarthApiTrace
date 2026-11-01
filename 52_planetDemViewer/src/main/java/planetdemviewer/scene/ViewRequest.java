package planetdemviewer.scene;

import java.util.Objects;
import planetdemviewer.spatial.Viewport;

/** Immutable camera/view work request consumed outside the model critical section. */
public record ViewRequest(String viewId, CameraSnapshot camera, Viewport viewport,
                          int priority, long deadlineNanos) {
    public ViewRequest {
        if (viewId == null || viewId.isBlank() || camera == null || viewport == null || deadlineNanos < 0L) {
            throw new IllegalArgumentException("A view request requires a view, camera, viewport, and deadline");
        }
    }

    public static ViewRequest immediate(String viewId, CameraSnapshot camera, Viewport viewport, int priority) {
        Objects.requireNonNull(viewId, "viewId");
        return new ViewRequest(viewId, camera, viewport, priority, System.nanoTime());
    }
}
