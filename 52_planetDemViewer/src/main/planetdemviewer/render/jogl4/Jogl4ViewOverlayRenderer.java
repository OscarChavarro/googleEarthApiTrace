package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import planetdemviewer.render.ViewBorder;
import planetdemviewer.render.ViewOverlayPlanner;
import planetdemviewer.render.View;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.light.Light;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4LineRenderer;
import vsdk.toolkit.render.jogl.Jogl4SphereRenderer;

/** Draws what decorates a view above its terrain, as its planner decides. */
final class Jogl4ViewOverlayRenderer {
    private final ViewOverlayPlanner planner;
    private final Jogl4TextRenderer text;

    Jogl4ViewOverlayRenderer(ViewOverlayPlanner planner, Jogl4TextRenderer text) {
        this.planner = planner;
        this.text = text;
    }

    void draw(GL4 gl, View view, Light light, int[] viewport, int canvasWidth, int canvasHeight) {
        for (Camera other : planner.otherCameras(view)) {
            Jogl4CameraRenderer.draw(gl, other, view.getCamera().calculateProjectionMatrix());
        }
        Matrix4x4d marker = planner.selectionMarkerMatrix(view, viewport);
        if (marker != null) {
            gl.glEnable(GL.GL_DEPTH_TEST);
            Jogl4SphereRenderer.draw(gl, planner.markerSphere(), view.getCamera(), light,
                planner.markerMaterial(), planner.markerQuality(), null, null, marker, 16, 12);
        }
        ViewBorder border = planner.border(view, viewport, canvasWidth, canvasHeight);
        if (border != null) {
            gl.glViewport(0, 0, canvasWidth, canvasHeight);
            gl.glDisable(GL.GL_DEPTH_TEST);
            Jogl4LineRenderer.drawLines(gl, Matrix4x4d.identityMatrix(), border.positions(), border.colors(),
                border.lineWidth());
            text.draw(gl, border.title(), canvasWidth, canvasHeight);
        }
    }
}
