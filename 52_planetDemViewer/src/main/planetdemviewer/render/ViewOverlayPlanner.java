package planetdemviewer.render;

import java.util.ArrayList;
import java.util.List;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.geometry.volume.Sphere;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;

/** Decides what decorates a view above its terrain: other cameras' frustums, the selection marker, the border. */
public final class ViewOverlayPlanner {
    private static final double MARKER_PIXELS = 14.0;

    private final PlanetViewerModel model;
    private final ViewportLayout viewports;
    private final Sphere markerSphere = new Sphere(1.0);
    private final RendererConfiguration markerQuality = RenderStyles.markerQuality();
    private final SimpleMaterial markerMaterial = RenderStyles.markerMaterial();
    private boolean cameraFrustumsVisible = true;

    ViewOverlayPlanner(PlanetViewerModel model, ViewportLayout viewports) {
        this.model = model;
        this.viewports = viewports;
    }

    void toggleCameraFrustumsVisible() {
        cameraFrustumsVisible = !cameraFrustumsVisible;
    }

    /**
     * Lets viewports other than the first one see the other viewports' cameras as cyan
     * frustum gizmos. By convention, the first (main) view never shows camera frustums.
     */
    public List<Camera> otherCameras(View view) {
        List<Camera> cameras = new ArrayList<>();
        if (!cameraFrustumsVisible || viewports.views().size() < 2 || view == viewports.mainView()) {
            return cameras;
        }
        for (View other : viewports.views()) {
            if (other != view && other.isActive()) {
                cameras.add(other.getCamera());
            }
        }
        return cameras;
    }

    /** Keeps the marker at a constant size on screen. Returns null when there is no marker to draw. */
    public Matrix4x4d selectionMarkerMatrix(View view, int[] viewport) {
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        if (selection == null || model.getOperationMode() != OperationModes.BASIC_TRIANGULATION) {
            return null;
        }
        WorldPosition position = selection.worldPosition();
        Vector3Dd cameraPosition = view.getCamera().getPosition();
        double distance = Math.sqrt(Math.pow(cameraPosition.x() - position.x(), 2)
            + Math.pow(cameraPosition.y() - position.y(), 2)
            + Math.pow(cameraPosition.z() - position.z(), 2));
        double radius = view.getCamera().getProjectionMode() == Camera.PROJECTION_MODE_ORTHOGONAL
            ? view.getCamera().getOrthogonalZoom() * MARKER_PIXELS / Math.max(1, viewport[3])
            : distance * Math.tan(Math.toRadians(view.getCamera().getFov() * 0.5))
                * MARKER_PIXELS / Math.max(1, viewport[3]);
        radius = Math.max(1e-8, radius);
        return new Matrix4x4d().translation(position.x(), position.y(), position.z())
            .multiply(new Matrix4x4d().scale(radius, radius, radius));
    }

    public Sphere markerSphere() {
        return markerSphere;
    }

    public RendererConfiguration markerQuality() {
        return markerQuality;
    }

    public SimpleMaterial markerMaterial() {
        return markerMaterial;
    }

    /** Returns null when a single view fills the canvas. */
    public ViewBorder border(View view, int[] viewport, int canvasWidth, int canvasHeight) {
        if (viewports.views().size() < 2) {
            return null;
        }
        float x0 = 2.0f * (viewport[0] + 1) / canvasWidth - 1.0f;
        float y0 = 2.0f * (viewport[1] + 1) / canvasHeight - 1.0f;
        float x1 = 2.0f * (viewport[0] + viewport[2] - 1) / canvasWidth - 1.0f;
        float y1 = 2.0f * (viewport[1] + viewport[3] - 1) / canvasHeight - 1.0f;
        float[] positions = {
            x0, y0, 0, x1, y0, 0,
            x1, y0, 0, x1, y1, 0,
            x1, y1, 0, x0, y1, 0,
            x0, y1, 0, x0, y0, 0
        };
        float r = view.isSelected() ? 1.0f : 0.4f;
        float g = view.isSelected() ? 0.85f : 0.4f;
        float[] colors = new float[positions.length];
        for (int i = 0; i < colors.length; i += 3) {
            colors[i] = r;
            colors[i + 1] = g;
            colors[i + 2] = 0.1f;
        }
        TextLabel title = TextLabel.left(HudContent.viewTitle(model, viewports, view), TextStyle.STATUS,
            viewport[0] + 6, viewport[1] + viewport[3] - 18, TextLabel.WHITE);
        return new ViewBorder(positions, colors, view.isSelected() ? 2.5f : 1.0f, title);
    }
}
