package planetdemviewer.render;

import java.util.function.Predicate;
import planetdemviewer.geo.GeographicSurfacePosition;
import planetdemviewer.model.CameraControlMode;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.processing.QuadtreeDrawPlanner;
import planetdemviewer.render.terrain.TerrainPickHit;
import planetdemviewer.render.terrain.TerrainPicker;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.KeyEvent;

/**
 * The keys of the levels browser camera control. The pairs x/X and y/Y move the camera west/east
 * and south/north by the side of a quadtree cell, so whatever is at some distance from the center
 * of a cell ends at that same distance from the center of the neighbor. The pair z/Z halves or
 * doubles the camera's height over the z = 0 plane: tile selection works by projected area, so
 * this usually changes the level by one.
 *
 * The moved cell is the one under the selected terrain point, or under the center of the view
 * when no point is selected. The selection jumps with the camera, because the neighbor may
 * not be loaded yet: its height and color tile are updated once that neighbor is drawn.
 */
public final class LevelsBrowserCameraControl {
    private final PlanetViewerModel model;
    private final TerrainPicker picker;
    private final Predicate<TileAddress> isDrawn;
    private TerrainSurfaceSelection provisionalSelection;

    public LevelsBrowserCameraControl(PlanetViewerModel model, TerrainPicker picker, Predicate<TileAddress> isDrawn) {
        this.model = model;
        this.picker = picker;
        this.isDrawn = isDrawn;
    }

    /** @return whether the key belongs to this camera control */
    public boolean processKeyPressed(int keycode, Camera camera) {
        if (model.getCameraControlMode() != CameraControlMode.LEVELS_BROWSER) {
            return false;
        }
        switch (keycode) {
            case KeyEvent.KEY_x -> jumpToNeighbor(-1, 0, camera);
            case KeyEvent.KEY_X -> jumpToNeighbor(1, 0, camera);
            case KeyEvent.KEY_y -> jumpToNeighbor(0, -1, camera);
            case KeyEvent.KEY_Y -> jumpToNeighbor(0, 1, camera);
            case KeyEvent.KEY_z -> scaleHeight(0.5, camera);
            case KeyEvent.KEY_Z -> scaleHeight(2.0, camera);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void scaleHeight(double factor, Camera camera) {
        Vector3Dd position = camera.getPosition();
        camera.setPosition(position.withZ(position.z() * factor));
    }

    private void jumpToNeighbor(int columns, int rows, Camera camera) {
        PyramidalImageInstance instance = model.getSelectedInstance();
        if (instance == null) {
            return;
        }
        TerrainSurfaceSelection selection = currentSelection(instance);
        TileAddress cell = selection != null ? selection.tile() : cellAtViewCenter(instance, camera);
        TileAddress neighbor = cell == null ? null : cell.neighbor(columns, rows);
        if (neighbor == null) {
            return;
        }
        double worldStep = cell.size() * model.relativeScale(instance.getPsc());
        camera.setPosition(camera.getPosition().add(new Vector3Dd(columns * worldStep, rows * worldStep, 0.0)));
        if (selection != null) {
            provisionalSelection = selectionInNeighbor(selection, neighbor, columns, rows, worldStep);
            model.setTerrainSurfaceSelection(provisionalSelection);
        }
    }

    /**
     * The selected point, in the cell that the last frame drew there. That can be of another level
     * than the cell that was clicked, after a change of height.
     */
    private TerrainSurfaceSelection currentSelection(PyramidalImageInstance instance) {
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        if (selection == null || selection == provisionalSelection) {
            return selection;
        }
        TerrainSurfaceSelection drawn = surfaceBelow(selection.position(), instance);
        return drawn != null ? drawn : selection;
    }

    private TerrainSurfaceSelection surfaceBelow(GeographicSurfacePosition position, PyramidalImageInstance instance) {
        double scale = model.relativeScale(instance.getPsc());
        double worldX = ((position.longitude() + 180.0) / 360.0 - 0.5) * scale + instance.getOffsetX();
        double worldY = ((position.latitude() + 90.0) / 180.0 - 0.5) * scale + instance.getOffsetY();
        // The whole width of the world is well above the highest terrain, at any height exaggeration in use.
        return picker.surfaceBelow(worldX, worldY, scale * Math.max(1.0, model.getHeightExagerationFactor()));
    }

    /** The cell drawn at the center of the view or, in the palette image or over no terrain, the one selected for it. */
    private TileAddress cellAtViewCenter(PyramidalImageInstance instance, Camera camera) {
        TerrainPickHit hit = picker.hitAtViewCenter();
        if (hit != null) {
            return TileAddress.fabdem(hit.command().node().getId());
        }
        Ray3 ray = picker.viewCenterRay();
        if (ray.direction().z() >= 0.0) {
            return null;
        }
        double distance = (instance.getZOffset() * 1e-4 - ray.origin().z()) / ray.direction().z();
        double scale = model.relativeScale(instance.getPsc());
        double localX = (ray.origin().x() + ray.direction().x() * distance - instance.getOffsetX()) / scale + 0.5;
        double localY = (ray.origin().y() + ray.direction().y() * distance - instance.getOffsetY()) / scale + 0.5;
        for (DrawCommand command : QuadtreeDrawPlanner.select(instance, camera, scale)) {
            if (localX >= command.node().getX0() && localX < command.node().getX1()
                && localY >= command.node().getY0() && localY < command.node().getY1()) {
                return TileAddress.fabdem(command.node().getId());
            }
        }
        return null;
    }

    private static TerrainSurfaceSelection selectionInNeighbor(TerrainSurfaceSelection selection, TileAddress neighbor,
                                                               int columns, int rows, double worldStep) {
        double size = neighbor.size();
        GeographicSurfacePosition position = new GeographicSurfacePosition(
            selection.position().longitude() + columns * size * 360.0,
            selection.position().latitude() + rows * size * 180.0);
        WorldPosition world = selection.worldPosition();
        return TerrainSurfaceSelection.inTile(position,
            new WorldPosition(world.x() + columns * worldStep, world.y() + rows * worldStep, world.z()),
            neighbor, null, selection.heightAboveMeanSeaLevelMetres());
    }

    /** Gives a selection that jumped its height and color tile, once a frame has drawn its neighbor. */
    public void frameDrawn() {
        TerrainSurfaceSelection current = model.getTerrainSurfaceSelection();
        if (provisionalSelection == null) {
            return;
        }
        if (current == null || !sameTile(current, provisionalSelection)) {
            provisionalSelection = null;
            return;
        }
        PyramidalImageInstance instance = model.getSelectedInstance();
        if (instance == null || !isDrawn.test(current.tile())) {
            return;
        }
        TerrainSurfaceSelection drawn = surfaceBelow(current.position(), instance);
        if (drawn != null && drawn.tile().equals(current.tile())) {
            model.setTerrainSurfaceSelection(drawn);
        }
        provisionalSelection = null;
    }

    private static boolean sameTile(TerrainSurfaceSelection first, TerrainSurfaceSelection second) {
        return first.tile().equals(second.tile()) && first.position() == second.position();
    }
}
