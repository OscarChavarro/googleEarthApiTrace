package planetdemviewer.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResourceTelemetryTest {
    @Test
    void snapshotsUsageIndependentlyByResourceType() {
        ResourceTelemetry telemetry = new ResourceTelemetry();
        telemetry.setBytes(ResourceType.ELEVATION_RAM, 42L);
        telemetry.setBytes(ResourceType.TERRAIN_GPU, 84L);

        assertEquals(42L, telemetry.bytes(ResourceType.ELEVATION_RAM));
        assertEquals(84L, telemetry.snapshot().get(ResourceType.TERRAIN_GPU));
        assertEquals(0L, telemetry.snapshot().get(ResourceType.RASTER_GPU));
        assertEquals(-1L, new ResourceBudget(Map.of()).limitBytes(ResourceType.RASTER_RAM));
    }
}
