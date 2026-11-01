package pyramidalimagecoverage.model;

/** Level-10 diagnostics for the 8x8-degree world capture groups. */
public enum GridModes {
    SHOW_NOTHING,
    SHOW_ALL_GROUPS,
    SHOW_MOSTLY_EMPTY_GROUPS,
    SHOW_GROUPS_BEING_DOWNLOADED;

    public GridModes next() {
        GridModes[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}
