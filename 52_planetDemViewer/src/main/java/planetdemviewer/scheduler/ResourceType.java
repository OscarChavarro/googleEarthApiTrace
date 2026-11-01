package planetdemviewer.scheduler;

/** Independently budgeted resources, regardless of which layer or backend owns them. */
public enum ResourceType {
    ELEVATION_RAM,
    RASTER_RAM,
    TERRAIN_GPU,
    RASTER_GPU
}
