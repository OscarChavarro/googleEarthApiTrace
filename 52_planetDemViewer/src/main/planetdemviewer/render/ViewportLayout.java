package planetdemviewer.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.gui.CameraControllerAquynza;

/**
 * The canvas's 1 to 4 views (ported from the old prototype's JoglView and
 * ViewOrganizer). One shared camera controller is retargeted to whichever view
 * is selected, so keyboard and mouse interaction apply to that view. It does
 * not touch OpenGL.
 */
public final class ViewportLayout {
    private static final int MAX_VIEWS = 4;

    private final CameraControllerAquynza cameraController;
    private final List<View> views = new ArrayList<>();
    private int layoutStyle;
    private int fullScreenViewIndex = -1;

    public ViewportLayout(Camera mainCamera) {
        View mainView = new View("View 1", mainCamera);
        mainView.setSelected(true);
        views.add(mainView);
        cameraController = new CameraControllerAquynza(mainCamera);
        cameraController.setDeltaMovement(0.2);
    }

    public CameraControllerAquynza cameraController() {
        return cameraController;
    }

    public List<View> views() {
        return Collections.unmodifiableList(views);
    }

    /** The view whose camera drives culling, LOD selection and PSC renormalization. */
    public View mainView() {
        return views.get(0);
    }

    public View selectedView() {
        return views.get(indexOfSelected());
    }

    public RendererConfiguration selectedRenderingConfiguration() {
        return selectedView().getRenderingConfiguration();
    }

    /** The 1-based number shown to the user. */
    public int numberOf(View view) {
        return views.indexOf(view) + 1;
    }

    public int layoutStyle() {
        return layoutStyle;
    }

    public boolean isFullScreen() {
        return fullScreenViewIndex >= 0;
    }

    public void addView() {
        if (views.size() >= MAX_VIEWS) {
            return;
        }
        Camera camera = new Camera();
        Vector3Dd sourcePosition = mainView().getCamera().getPosition();
        camera.setPosition(new Vector3Dd(sourcePosition.x(), sourcePosition.y(), sourcePosition.z()));
        camera.setFocusedPositionDirect(new Vector3Dd(0.0, 0.0, 0.0));
        camera.setUpDirect(new Vector3Dd(0.0, 1.0, 0.0));
        camera.updateVectors();
        views.add(new View("View " + (views.size() + 1), camera));
        selectView(views.size() - 1);
    }

    public void removeView() {
        if (views.size() <= 1) {
            return;
        }
        views.remove(views.size() - 1);
        if (fullScreenViewIndex >= views.size()) {
            fullScreenViewIndex = -1;
        }
        selectView(0);
    }

    public void cycleSelectedView() {
        selectView((indexOfSelected() + 1) % views.size());
    }

    public void cycleLayoutStyle() {
        layoutStyle++;
        relayout();
    }

    /** Routes a click at this canvas pixel to the view under it. */
    public void selectViewAtPixel(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight) {
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            return;
        }
        double xPercent = (double) xPixel / canvasWidth;
        double yPercent = 1.0 - (double) yPixelFromTop / canvasHeight;
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).isActive() && views.get(i).inside(xPercent, yPercent)) {
                selectView(i);
                return;
            }
        }
    }

    private void selectView(int index) {
        for (int i = 0; i < views.size(); i++) {
            views.get(i).setSelected(i == index);
        }
        relayout();
    }

    private int indexOfSelected() {
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).isSelected()) {
                return i;
            }
        }
        return 0;
    }

    private void relayout() {
        int selected = ViewOrganizer.doLayout(views, fullScreenViewIndex, layoutStyle);
        cameraController.setCamera(views.get(selected).getCamera());
    }
}
