package planetviewer.render;

import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;

/**
 * One viewport inside the canvas: its own camera and rendering
 * configuration, a viewport rectangle in percent coordinates (as laid out
 * by ViewOrganizer), a title, and active/selected flags. Ported from the
 * old prototype's JoglView.
 */
public final class View {
    private final Camera camera;
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private String title;
    private double viewportStartXPercent;
    private double viewportStartYPercent;
    private double viewportSizeXPercent = 1.0;
    private double viewportSizeYPercent = 1.0;
    private boolean active = true;
    private boolean selected;

    public View(String title, Camera camera) {
        this.title = title;
        this.camera = camera;
    }

    public Camera getCamera() {
        return camera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public double getViewportStartXPercent() {
        return viewportStartXPercent;
    }

    public void setViewportStartXPercent(double v) {
        this.viewportStartXPercent = v;
    }

    public double getViewportStartYPercent() {
        return viewportStartYPercent;
    }

    public void setViewportStartYPercent(double v) {
        this.viewportStartYPercent = v;
    }

    public double getViewportSizeXPercent() {
        return viewportSizeXPercent;
    }

    public void setViewportSizeXPercent(double v) {
        this.viewportSizeXPercent = v;
    }

    public double getViewportSizeYPercent() {
        return viewportSizeYPercent;
    }

    public void setViewportSizeYPercent(double v) {
        this.viewportSizeYPercent = v;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /**
     * Viewport rectangle in GL pixel coordinates for the given canvas size.
     * Both the percent coordinates and glViewport use a bottom-left origin,
     * so no vertical flip is needed here.
     */
    public int[] viewportInPixels(int canvasWidth, int canvasHeight) {
        int x = (int) Math.round(viewportStartXPercent * canvasWidth);
        int y = (int) Math.round(viewportStartYPercent * canvasHeight);
        int w = (int) Math.round(viewportSizeXPercent * canvasWidth);
        int h = (int) Math.round(viewportSizeYPercent * canvasHeight);
        return new int[] {x, y, Math.max(1, w), Math.max(1, h)};
    }

    public boolean inside(double xPercent, double yPercent) {
        return xPercent >= viewportStartXPercent && xPercent <= viewportStartXPercent + viewportSizeXPercent
            && yPercent >= viewportStartYPercent && yPercent <= viewportStartYPercent + viewportSizeYPercent;
    }
}
