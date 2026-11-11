package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import planetdemviewer.geo.GeographicSurfacePosition;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.model.CameraControlMode;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.render.terrain.TerrainPickHit;
import planetdemviewer.render.terrain.TerrainPicker;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.scheduler.ResourceTelemetry;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.KeyEvent;

class LevelsBrowserCameraControlTest {
    private static final double TOLERANCE = 1e-9;

    private final PlanetViewerModel model = new PlanetViewerModel();
    private TileImageLoader loader;
    private LevelsBrowserCameraControl control;
    private QuadtreeNode drawnNode;
    private boolean neighborDrawn;
    private Camera camera;

    /** The nodes of a quadkey, from the root; the last one is the tile. */
    private static QuadtreeNode node(String quadkey) {
        QuadtreeNode node = new QuadtreeNode("0", null, null);
        for (int length = 2; length <= quadkey.length(); length++) {
            node = new QuadtreeNode(quadkey.substring(0, length), node, null);
        }
        return node;
    }

    private TerrainPickHit hit(Ray3 ray) {
        if (drawnNode == null) {
            return null;
        }
        double scale = model.relativeScale(1);
        double localX = ray.origin().x() / scale + 0.5;
        double localY = ray.origin().y() / scale + 0.5;
        return new TerrainPickHit(new DrawCommand(drawnNode, new Vector3Dd[0]),
            new WorldPosition(ray.origin().x(), ray.origin().y(), 0.0), 1.0, localX, localY, 100.0, null);
    }

    @BeforeEach
    void setUp() {
        loader = new TileImageLoader(model.getPalettes(), model.getStorageProfile(), new ResourceTelemetry());
        model.setCameraControlMode(CameraControlMode.LEVELS_BROWSER);
        String source = new File("build/test-dem-source").getAbsolutePath();
        model.addImage(new PyramidalImage(source, new QuadtreeNode("0", null, null)));
        camera = model.getViewingCamera();
        camera.setPosition(new Vector3Dd(0.0, 0.0, 5.0));
        TerrainPicker picker = new TerrainPicker(model, new ViewportLayout(camera), this::hit, loader);
        picker.setRenderedSurfaceSize(800, 600);
        control = new LevelsBrowserCameraControl(model, picker, tile -> neighborDrawn);
    }

    @AfterEach
    void tearDown() {
        loader.shutdown();
    }

    private static TerrainSurfaceSelection clickedPoint(String quadkey, double longitudeFromCenter,
                                                        double latitudeFromCenter, double scale) {
        TileAddress tile = TileAddress.fabdem(quadkey);
        double longitude = (tile.x0() + tile.size() * 0.5) * 360.0 - 180.0 + longitudeFromCenter;
        double latitude = (tile.y0() + tile.size() * 0.5) * 180.0 - 90.0 + latitudeFromCenter;
        return TerrainSurfaceSelection.inTile(new GeographicSurfacePosition(longitude, latitude),
            new WorldPosition(((longitude + 180.0) / 360.0 - 0.5) * scale,
                ((latitude + 90.0) / 180.0 - 0.5) * scale, 0.0), tile, null, 10.0);
    }

    @Test
    void aquynzaKeepsItsOwnKeys() {
        model.setCameraControlMode(CameraControlMode.AQUYNZA);
        assertFalse(control.processKeyPressed(KeyEvent.KEY_x, camera));
        assertEquals(new Vector3Dd(0.0, 0.0, 5.0).x(), camera.getPosition().x());
    }

    @Test
    void otherKeysAreLeftToTheController() {
        assertFalse(control.processKeyPressed(KeyEvent.KEY_a, camera));
    }

    @Test
    void zHalvesAndDoublesTheHeightOnly() {
        camera.setPosition(new Vector3Dd(1.0, 2.0, 8.0));
        assertTrue(control.processKeyPressed(KeyEvent.KEY_z, camera));
        assertEquals(new Vector3Dd(1.0, 2.0, 4.0).z(), camera.getPosition().z());
        assertEquals(1.0, camera.getPosition().x());
        control.processKeyPressed(KeyEvent.KEY_Z, camera);
        control.processKeyPressed(KeyEvent.KEY_Z, camera);
        assertEquals(16.0, camera.getPosition().z());
    }

    @Test
    void withoutSelectionTheJumpIsTheCellAtTheCenterOfTheView() {
        drawnNode = node("0123");
        double scale = model.relativeScale(1);
        double step = TileAddress.fabdem("0123").size() * scale;

        control.processKeyPressed(KeyEvent.KEY_X, camera);
        assertEquals(step, camera.getPosition().x(), TOLERANCE);
        control.processKeyPressed(KeyEvent.KEY_Y, camera);
        assertEquals(step, camera.getPosition().y(), TOLERANCE);
        control.processKeyPressed(KeyEvent.KEY_x, camera);
        assertEquals(0.0, camera.getPosition().x(), TOLERANCE);
        control.processKeyPressed(KeyEvent.KEY_y, camera);
        assertEquals(0.0, camera.getPosition().y(), TOLERANCE);
        assertEquals(5.0, camera.getPosition().z());
    }

    @Test
    void theJumpKeepsTheCameraOrientation() {
        drawnNode = node("0123");
        camera.setFocusedPositionDirect(new Vector3Dd(0.0, 0.0, 0.0));
        camera.updateVectors();
        Vector3Dd front = camera.getFront();
        control.processKeyPressed(KeyEvent.KEY_X, camera);
        assertEquals(front.x(), camera.getFront().x());
        assertEquals(front.y(), camera.getFront().y());
        assertEquals(front.z(), camera.getFront().z());
    }

    @Test
    void theSelectedPointKeepsItsOffsetInTheEasternNeighbor() {
        TerrainSurfaceSelection clicked = clickedPoint("0123", 3.0, -2.0, model.relativeScale(1));
        model.setTerrainSurfaceSelection(clicked);

        control.processKeyPressed(KeyEvent.KEY_X, camera);

        TerrainSurfaceSelection jumped = model.getTerrainSurfaceSelection();
        assertEquals(TileAddress.fabdem("0123").neighbor(1, 0), jumped.tile());
        assertEquals(clicked.geoidXMetres(), jumped.geoidXMetres(), 1e-6);
        assertEquals(clicked.geoidYMetres(), jumped.geoidYMetres(), 1e-6);
        double step = clicked.tile().size();
        assertEquals(clicked.position().longitude() + step * 360.0, jumped.position().longitude(), TOLERANCE);
        assertEquals(step * model.relativeScale(1), camera.getPosition().x(), TOLERANCE);
        assertEquals(clicked.worldPosition().x() + camera.getPosition().x(), jumped.worldPosition().x(), TOLERANCE);
    }

    @Test
    void theSelectedPointKeepsItsDistanceInLatitudeInTheNorthernNeighbor() {
        TerrainSurfaceSelection clicked = clickedPoint("0123", 3.0, -2.0, model.relativeScale(1));
        model.setTerrainSurfaceSelection(clicked);

        control.processKeyPressed(KeyEvent.KEY_Y, camera);

        TerrainSurfaceSelection jumped = model.getTerrainSurfaceSelection();
        assertEquals(TileAddress.fabdem("0123").neighbor(0, 1), jumped.tile());
        assertEquals(clicked.geoidYMetres(), jumped.geoidYMetres(), 1e-6);
        assertEquals(clicked.position().longitude(), jumped.position().longitude(), TOLERANCE);
    }

    @Test
    void afterAChangeOfLevelTheJumpIsTheCellDrawnAtTheSelectedPoint() {
        TerrainSurfaceSelection clicked = clickedPoint("010", 3.0, 3.0, model.relativeScale(1));
        model.setTerrainSurfaceSelection(clicked);
        drawnNode = node("0102");

        control.processKeyPressed(KeyEvent.KEY_X, camera);

        assertEquals(TileAddress.fabdem("0102").size() * model.relativeScale(1), camera.getPosition().x(), TOLERANCE);
        assertEquals(TileAddress.fabdem("0102").neighbor(1, 0), model.getTerrainSurfaceSelection().tile());
    }

    @Test
    void twoJumpsBeforeAFrameUseTheCellOfTheFirst() {
        TerrainSurfaceSelection clicked = clickedPoint("0123", 3.0, 0.0, model.relativeScale(1));
        model.setTerrainSurfaceSelection(clicked);
        drawnNode = node("0123");

        control.processKeyPressed(KeyEvent.KEY_x, camera);
        control.processKeyPressed(KeyEvent.KEY_x, camera);

        TileAddress start = TileAddress.fabdem("0123");
        assertEquals(start.neighbor(-2, 0), model.getTerrainSurfaceSelection().tile());
        assertEquals(-2.0 * start.size() * model.relativeScale(1), camera.getPosition().x(), TOLERANCE);
    }

    @Test
    void noJumpBeyondTheRootTile() {
        drawnNode = node("00");
        control.processKeyPressed(KeyEvent.KEY_x, camera);
        assertEquals(0.0, camera.getPosition().x());
        control.processKeyPressed(KeyEvent.KEY_y, camera);
        assertEquals(0.0, camera.getPosition().y());
    }

    @Test
    void theSelectionGetsItsHeightOnceTheNeighborIsDrawn() {
        TerrainSurfaceSelection clicked = clickedPoint("0123", 3.0, -2.0, model.relativeScale(1));
        model.setTerrainSurfaceSelection(clicked);
        control.processKeyPressed(KeyEvent.KEY_X, camera);
        TerrainSurfaceSelection provisional = model.getTerrainSurfaceSelection();

        control.frameDrawn();
        assertSame(provisional, model.getTerrainSurfaceSelection());

        drawnNode = node(provisional.tile().quadkey());
        neighborDrawn = true;
        control.frameDrawn();
        TerrainSurfaceSelection resolved = model.getTerrainSurfaceSelection();
        assertEquals(provisional.tile(), resolved.tile());
        assertEquals(provisional.geoidXMetres(), resolved.geoidXMetres(), 1e-6);
        assertEquals(provisional.geoidYMetres(), resolved.geoidYMetres(), 1e-6);
    }

    @Test
    void aClickDuringTheWaitIsNotOverwritten() {
        model.setTerrainSurfaceSelection(clickedPoint("0123", 3.0, -2.0, model.relativeScale(1)));
        control.processKeyPressed(KeyEvent.KEY_X, camera);
        TerrainSurfaceSelection clickedLater = clickedPoint("0330", 1.0, 1.0, model.relativeScale(1));
        model.setTerrainSurfaceSelection(clickedLater);

        neighborDrawn = true;
        drawnNode = node("0123");
        control.frameDrawn();
        assertSame(clickedLater, model.getTerrainSurfaceSelection());
    }

    @Test
    void aClearedSelectionStaysCleared() {
        model.setTerrainSurfaceSelection(clickedPoint("0123", 3.0, -2.0, model.relativeScale(1)));
        control.processKeyPressed(KeyEvent.KEY_X, camera);
        model.setTerrainSurfaceSelection(null);
        neighborDrawn = true;
        control.frameDrawn();
        assertNull(model.getTerrainSurfaceSelection());
    }
}
