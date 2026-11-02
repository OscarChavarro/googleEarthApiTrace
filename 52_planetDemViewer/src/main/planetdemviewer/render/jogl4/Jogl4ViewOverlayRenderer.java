package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.awt.Font;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.View;
import planetdemviewer.render.ViewportLayout;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.geometry.volume.Sphere;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4LineRenderer;
import vsdk.toolkit.render.jogl.Jogl4SphereRenderer;

/** Draws what decorates a view above its terrain: other cameras' frustums, the selection marker, the border. */
final class Jogl4ViewOverlayRenderer {
    private final PlanetViewerModel model;
    private final ViewportLayout viewports;
    private final Sphere markerSphere = new Sphere(1.0);
    private final RendererConfiguration markerQuality = markerQuality();
    private final SimpleMaterial markerMaterial = markerMaterial();
    private boolean cameraFrustumsVisible = true;
    private Jogl4TextRenderer titleText;

    Jogl4ViewOverlayRenderer(PlanetViewerModel model, ViewportLayout viewports) {
        this.model = model;
        this.viewports = viewports;
    }

    void init() {
        titleText = new Jogl4TextRenderer(new Font("SansSerif", Font.BOLD, 16));
    }

    void dispose(GL4 gl) {
        if (titleText != null) {
            titleText.dispose(gl);
            titleText = null;
        }
    }

    void toggleCameraFrustumsVisible() {
        cameraFrustumsVisible = !cameraFrustumsVisible;
    }

    void draw(GL4 gl, View view, int[] viewport, int canvasWidth, int canvasHeight) {
        drawOtherCameraFrustums(gl, view);
        drawSelectionMarker(gl, view, viewport);
        drawBorder(gl, view, viewport, canvasWidth, canvasHeight);
    }

    /**
     * Lets viewports other than the first one see the other viewports' cameras as cyan
     * frustum gizmos. By convention, the first (main) view never shows camera frustums.
     */
    private void drawOtherCameraFrustums(GL4 gl, View view) {
        if (!cameraFrustumsVisible || viewports.views().size() < 2 || view == viewports.mainView()) {
            return;
        }
        Matrix4x4d viewerProjection = view.getCamera().calculateProjectionMatrix();
        for (View other : viewports.views()) {
            if (other == view || !other.isActive()) {
                continue;
            }
            Jogl4CameraRenderer.draw(gl, other.getCamera(), viewerProjection);
        }
    }

    private void drawSelectionMarker(GL4 gl, View view, int[] viewport) {
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        if (selection == null || model.getOperationMode() != OperationModes.BASIC_TRIANGULATION) {
            return;
        }
        WorldPosition position = selection.worldPosition();
        Vector3Dd cameraPosition = view.getCamera().getPosition();
        double distance = Math.sqrt(Math.pow(cameraPosition.x() - position.x(), 2)
            + Math.pow(cameraPosition.y() - position.y(), 2)
            + Math.pow(cameraPosition.z() - position.z(), 2));
        double radius = view.getCamera().getProjectionMode() == Camera.PROJECTION_MODE_ORTHOGONAL
            ? view.getCamera().getOrthogonalZoom() * 14.0 / Math.max(1, viewport[3])
            : distance * Math.tan(Math.toRadians(view.getCamera().getFov() * 0.5))
                * 14.0 / Math.max(1, viewport[3]);
        radius = Math.max(1e-8, radius);
        Matrix4x4d matrix = new Matrix4x4d().translation(position.x(), position.y(), position.z())
            .multiply(new Matrix4x4d().scale(radius, radius, radius));
        gl.glEnable(GL.GL_DEPTH_TEST);
        Jogl4SphereRenderer.draw(gl, markerSphere, view.getCamera(), model.getTerrainLight(),
            markerMaterial, markerQuality, null, null, matrix, 16, 12);
    }

    private void drawBorder(GL4 gl, View view, int[] viewport, int canvasWidth, int canvasHeight) {
        if (viewports.views().size() < 2) {
            return;
        }
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glDisable(GL.GL_DEPTH_TEST);
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
        Jogl4LineRenderer.drawLines(gl, Matrix4x4d.identityMatrix(), positions, colors,
            view.isSelected() ? 2.5f : 1.0f);
        if (titleText != null) {
            titleText.draw(gl, Jogl4HudRenderer.viewTitle(model, viewports, view), viewport[0] + 6,
                viewport[1] + viewport[3] - 18, canvasWidth, canvasHeight);
        }
    }

    private static RendererConfiguration markerQuality() {
        RendererConfiguration quality = new RendererConfiguration();
        quality.setSurfaces(true);
        quality.setWires(false);
        quality.setTexture(false);
        quality.setBumpMap(false);
        quality.setShadingType(RendererConfiguration.SHADING_TYPE_PHONG);
        return quality;
    }

    private static SimpleMaterial markerMaterial() {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.10, 0.45, 0.75));
        material = material.withDiffuse(new ColorRgb(0.30, 0.80, 1.00));
        return material.withSpecular(new ColorRgb(0.70, 0.90, 1.00)).withPhongExponent(24.0);
    }
}
