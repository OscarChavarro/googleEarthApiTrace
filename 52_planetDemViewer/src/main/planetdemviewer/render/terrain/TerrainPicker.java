package planetdemviewer.render.terrain;

import planetdemviewer.config.Configuration;
import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.geo.GeographicSurfacePosition;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.View;
import planetdemviewer.render.ViewportLayout;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector4Dd;
import vsdk.toolkit.environment.camera.Camera;

/** Turns a pixel into the terrain surface point and tile under it, and publishes them. */
public final class TerrainPicker {
    private final PlanetViewerModel model;
    private final ViewportLayout viewports;
    private final TerrainRayIntersector terrain;
    private final TileImageLoader tileImageLoader;
    // JOGL's drawable surface may use a higher pixel density than AWT's logical canvas.
    // Keep the last surface extent so both native clicks and MCP capture pixels map to the
    // same raster that was used to render the terrain.
    private volatile int surfaceWidth = 1;
    private volatile int surfaceHeight = 1;
    private volatile TileAddress selectedTile;

    public TerrainPicker(PlanetViewerModel model, ViewportLayout viewports,
                         TerrainRayIntersector terrain, TileImageLoader tileImageLoader) {
        this.model = model;
        this.viewports = viewports;
        this.terrain = terrain;
        this.tileImageLoader = tileImageLoader;
    }

    public void setRenderedSurfaceSize(int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
    }

    public TileAddress selectedTile() {
        return selectedTile;
    }

    /** Native AWT events use logical pixels, which are scaled to JOGL's physical drawable pixels. */
    public void selectAtCanvasPixel(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight,
                                    boolean selectTile) {
        int width = surfaceWidth;
        int height = surfaceHeight;
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            model.setTerrainSurfaceSelection(null);
            return;
        }
        int surfaceX = (int) Math.floor((xPixel + 0.5) * width / canvasWidth);
        int surfaceY = (int) Math.floor((yPixelFromTop + 0.5) * height / canvasHeight);
        selectAtSurfacePixel(surfaceX, surfaceY, width, height, selectTile);
    }

    /** Selects from a physical top-left-origin JOGL surface pixel, as used by MCP captures. */
    public void selectAtSurfacePixel(int xPixel, int yPixelFromTop) {
        selectAtSurfacePixel(xPixel, yPixelFromTop, surfaceWidth, surfaceHeight, false);
    }

    private void selectAtSurfacePixel(int xPixel, int yPixelFromTop, int width, int height, boolean selectTile) {
        if (model.getOperationMode() != OperationModes.BASIC_TRIANGULATION) {
            clearSelection(selectTile);
            return;
        }
        View view = viewports.selectedView();
        int[] viewport = view.viewportInPixels(width, height);
        int viewportTop = height - viewport[1] - viewport[3];
        int localX = xPixel - viewport[0];
        int localY = yPixelFromTop - viewportTop;
        if (localX < 0 || localY < 0 || localX >= viewport[2] || localY >= viewport[3]) {
            clearSelection(selectTile);
            return;
        }
        Ray3 ray = viewportRay(view.getCamera(), localX, localY, viewport[2], viewport[3]);
        TerrainPickHit hit = terrain.intersect(ray);
        model.setTerrainSurfaceSelection(hit == null ? null : surfaceSelection(hit));
        if (selectTile) {
            selectedTile = hit == null ? null : TileAddress.fabdem(hit.command().node().getId());
        }
    }

    private void clearSelection(boolean selectTile) {
        model.setTerrainSurfaceSelection(null);
        if (selectTile) {
            selectedTile = null;
        }
    }

    /**
     * Reconstructs the picking ray by unprojecting with the exact Vitral matrix used by
     * rendering. This deliberately avoids an approximate camera-basis/FOV reconstruction:
     * Vitral's camera transform contains its own coordinate-system rotations.
     */
    static Ray3 viewportRay(Camera camera, int xPixel, int yPixelFromTop, int width, int height) {
        double x = ((xPixel + 0.5) / width) * 2.0 - 1.0;
        double y = 1.0 - ((yPixelFromTop + 0.5) / height) * 2.0;
        Matrix4x4d inverseProjection = camera.calculateProjectionMatrix().inverse();
        WorldPosition near = unproject(inverseProjection, x, y, -1.0);
        WorldPosition far = unproject(inverseProjection, x, y, 1.0);
        return new Ray3(near, new WorldPosition(far.x() - near.x(), far.y() - near.y(), far.z() - near.z()));
    }

    private static WorldPosition unproject(Matrix4x4d inverseProjection, double x, double y, double z) {
        Vector4Dd point = inverseProjection.multiply(new Vector4Dd(x, y, z, 1.0)).dividedByW();
        return new WorldPosition(point.x(), point.y(), point.z());
    }

    private TerrainSurfaceSelection surfaceSelection(TerrainPickHit hit) {
        TileAddress tile = TileAddress.fabdem(hit.command().node().getId());
        double longitude = hit.localX() * 360.0 - 180.0;
        double latitude = hit.localY() * 180.0 - 90.0;
        double centerLongitude = (hit.command().node().getX0() + hit.command().node().getX1()) * 180.0 - 180.0;
        double centerLatitude = (hit.command().node().getY0() + hit.command().node().getY1()) * 90.0 - 90.0;
        double radius = Configuration.WORLD_WIDTH_METRES / (2.0 * Math.PI);
        double geoidX = Math.toRadians(longitude - centerLongitude) * radius * Math.cos(Math.toRadians(centerLatitude));
        double geoidY = Math.toRadians(latitude - centerLatitude) * radius;
        GeographicSurfacePosition position = new GeographicSurfacePosition(longitude, latitude);
        double orthometricHeight = tileImageLoader.orthometricHeight(
            new GeographicCoordinate(longitude, latitude), hit.ellipsoidalElevationMetres());
        return new TerrainSurfaceSelection(position, hit.worldPosition(), tile, hit.imageryTile(),
            orthometricHeight, geoidX, geoidY);
    }
}
