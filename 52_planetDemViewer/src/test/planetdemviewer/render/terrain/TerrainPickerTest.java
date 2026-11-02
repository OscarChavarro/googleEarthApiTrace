package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import planetdemviewer.geo.GeographicSurfacePosition;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.render.ViewportLayout;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.scheduler.ResourceTelemetry;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

class TerrainPickerTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private TileImageLoader loader;
    private TerrainPicker picker;
    private TerrainPickHit nextHit;
    private Ray3 lastRay;

    @BeforeEach
    void createPicker() {
        loader = new TileImageLoader(model.getPalettes(), model.getStorageProfile(), new ResourceTelemetry());
        picker = new TerrainPicker(model, new ViewportLayout(model.getViewingCamera()), ray -> {
            lastRay = ray;
            return nextHit;
        }, loader);
        picker.setRenderedSurfaceSize(800, 600);
        model.setOperationMode(OperationModes.BASIC_TRIANGULATION);
        model.setTerrainSurfaceSelection(new TerrainSurfaceSelection(new GeographicSurfacePosition(1.0, 2.0),
            new WorldPosition(0.0, 0.0, 0.0), TileAddress.fabdem("0"), 0.0, 0.0, 0.0));
    }

    @AfterEach
    void shutDownLoader() {
        loader.shutdown();
    }

    private static Camera cameraLookingDownFrom(double height) {
        Camera camera = new Camera();
        camera.setPosition(new Vector3Dd(0, 0, height));
        camera.setFocusedPositionDirect(new Vector3Dd(0, 0, 0));
        camera.setUpDirect(new Vector3Dd(0, 1, 0));
        camera.updateVectors();
        camera.updateViewportResize(800, 600);
        return camera;
    }

    private static Vector3Dd direction(Ray3 ray) {
        WorldPosition d = ray.direction();
        double length = Math.sqrt(d.x() * d.x() + d.y() * d.y() + d.z() * d.z());
        return new Vector3Dd(d.x() / length, d.y() / length, d.z() / length);
    }

    @Test
    void theCenterRayFollowsTheCameraAxis() {
        Vector3Dd direction = direction(
            TerrainPicker.viewportRay(cameraLookingDownFrom(5.0), 400, 300, 801, 601));
        assertEquals(0.0, direction.x(), 1e-6);
        assertEquals(0.0, direction.y(), 1e-6);
        assertEquals(-1.0, direction.z(), 1e-6);
    }

    @Test
    void aCornerPixelOpensTheRayTowardsThatCorner() {
        Vector3Dd direction = direction(
            TerrainPicker.viewportRay(cameraLookingDownFrom(5.0), 0, 0, 800, 600));
        assertTrue(direction.x() < 0.0, "left pixel looks west");
        assertTrue(direction.y() > 0.0, "top pixel looks north");
        assertTrue(direction.z() < 0.0, "the ray goes down to the terrain");
    }

    private static TerrainPickHit hitOnTile(String quadkey, double localX, double localY) {
        QuadtreeNode root = new QuadtreeNode("0", null, new File("0.bin"));
        QuadtreeNode node = quadkey.equals("0") ? root : new QuadtreeNode(quadkey, root, new File(quadkey + ".bin"));
        return new TerrainPickHit(new DrawCommand(node, new Vector3Dd[0]),
            new WorldPosition(1.0, 2.0, 3.0), 10.0, localX, localY, 0.0);
    }

    @Test
    void aHitPublishesItsGeographicPositionAndTile() {
        nextHit = hitOnTile("0", 0.75, 0.25);
        picker.selectAtSurfacePixel(400, 300);
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        assertEquals(90.0, selection.position().longitude(), 1e-9);
        assertEquals(-45.0, selection.position().latitude(), 1e-9);
        assertEquals(TileAddress.fabdem("0"), selection.tile());
        assertEquals(1.0, selection.worldPosition().x(), 1e-12);
    }

    @Test
    void onlyAClickThatAsksForItSelectsTheTile() {
        nextHit = hitOnTile("0", 0.5, 0.5);
        picker.selectAtCanvasPixel(400, 300, 800, 600, false);
        assertNull(picker.selectedTile());
        picker.selectAtCanvasPixel(400, 300, 800, 600, true);
        assertEquals(TileAddress.fabdem("0"), picker.selectedTile());

        nextHit = null;
        picker.selectAtCanvasPixel(400, 300, 800, 600, false);
        assertEquals(TileAddress.fabdem("0"), picker.selectedTile());
        picker.selectAtCanvasPixel(400, 300, 800, 600, true);
        assertNull(picker.selectedTile());
    }

    @Test
    void logicalCanvasPixelsAreScaledToTheSurfaceThatWasRendered() {
        picker.setRenderedSurfaceSize(1600, 1200);
        picker.selectAtCanvasPixel(100, 50, 800, 600, false);
        Ray3 fromCanvas = lastRay;
        picker.selectAtSurfacePixel(201, 101);
        assertEquals(fromCanvas, lastRay);
    }

    @Test
    void aPixelOverNoTerrainClearsTheSelection() {
        picker.selectAtSurfacePixel(400, 300);
        assertNull(model.getTerrainSurfaceSelection());
        assertNull(picker.selectedTile());
    }

    @Test
    void aPixelOutsideTheViewportClearsTheSelection() {
        picker.selectAtSurfacePixel(5000, 5);
        assertNull(model.getTerrainSurfaceSelection());
    }

    @Test
    void theFlatImageModeHasNoTerrainToSelect() {
        assertNotNull(model.getTerrainSurfaceSelection());
        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        picker.selectAtSurfacePixel(400, 300);
        assertNull(model.getTerrainSurfaceSelection());
    }

    @Test
    void anEmptyCanvasClearsTheSelection() {
        picker.selectAtCanvasPixel(10, 10, 0, 0, true);
        assertNull(model.getTerrainSurfaceSelection());
    }
}
