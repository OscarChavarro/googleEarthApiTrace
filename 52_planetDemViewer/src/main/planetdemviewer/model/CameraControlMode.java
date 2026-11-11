package planetdemviewer.model;

/** How the keys move the camera; the mouse and the remaining keys work the same in every mode. */
public enum CameraControlMode {
    /** Vitral's CameraControllerAquynza, with the z and Z keys of CameraZoom. */
    AQUYNZA,
    /** The x, y and z pairs jump between cells and levels of the quadtree. See render.LevelsBrowserCameraControl. */
    LEVELS_BROWSER
}
