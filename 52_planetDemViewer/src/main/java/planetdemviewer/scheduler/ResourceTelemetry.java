package planetdemviewer.scheduler;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe resource accounting published by I/O, preparation, and render owners. */
public final class ResourceTelemetry {
    private final EnumMap<ResourceType, AtomicLong> usage = new EnumMap<>(ResourceType.class);

    public ResourceTelemetry() {
        for (ResourceType type : ResourceType.values()) {
            usage.put(type, new AtomicLong());
        }
    }

    public void setBytes(ResourceType type, long bytes) {
        if (type == null || bytes < 0L) {
            throw new IllegalArgumentException("Resource usage must be non-negative");
        }
        usage.get(type).set(bytes);
    }

    public long bytes(ResourceType type) {
        return usage.get(type).get();
    }

    public Map<ResourceType, Long> snapshot() {
        EnumMap<ResourceType, Long> copy = new EnumMap<>(ResourceType.class);
        for (ResourceType type : ResourceType.values()) {
            copy.put(type, bytes(type));
        }
        return Map.copyOf(copy);
    }
}
