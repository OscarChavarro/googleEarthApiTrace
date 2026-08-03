package planetdemviewer.model;

/** Rendering strategies available to the DEM viewer. */
public enum OperationModes {
    PALETTE_BASED_IMAGE,
    BASIC_TRIANGULATION;

    public OperationModes next() {
        OperationModes[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
