package planetviewer.model;

/**
 * One placement of a PyramidalImage in the scene: world translation, a PSC
 * ("Powers of Ten") level analogous to the old prototype's
 * CachedPyramidalImage#setUniversalExtent, a z stacking offset and an
 * opacity, so several images can be stacked on top of one another.
 */
public final class PyramidalImageInstance {
    private final PyramidalImage image;
    private double offsetX;
    private double offsetY;
    private int psc;
    private double zOffset;
    private double opacity;
    private boolean visible;

    public PyramidalImageInstance(PyramidalImage image) {
        this.image = image;
        this.offsetX = 0.0;
        this.offsetY = 0.0;
        // relativeScale(psc) = 10^(psc - currentPSC - 1): psc = 1 renders at
        // "native" size (scale 1) when the camera starts at currentPSC = 0,
        // i.e. camera z close to its initial position (see PlanetViewerModel).
        this.psc = 1;
        this.zOffset = 0.0;
        this.opacity = 1.0;
        this.visible = true;
    }

    public PyramidalImage getImage() {
        return image;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public void setOffsetX(double offsetX) {
        this.offsetX = offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public void setOffsetY(double offsetY) {
        this.offsetY = offsetY;
    }

    public int getPsc() {
        return psc;
    }

    public void setPsc(int psc) {
        this.psc = psc;
    }

    public double getZOffset() {
        return zOffset;
    }

    public void setZOffset(double zOffset) {
        this.zOffset = zOffset;
    }

    public double getOpacity() {
        return opacity;
    }

    public void setOpacity(double opacity) {
        this.opacity = Math.max(0.0001, Math.min(1.0, opacity));
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
