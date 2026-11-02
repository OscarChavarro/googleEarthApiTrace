package planetdemviewer.scheduler;

import java.util.EnumMap;
import java.util.Map;

/** Immutable per-resource capacity limits; a negative value means unbounded. */
public final class ResourceBudget {
    private final Map<ResourceType, Long> limits;

    public ResourceBudget(Map<ResourceType, Long> limits) {
        EnumMap<ResourceType, Long> copy = new EnumMap<>(ResourceType.class);
        for (ResourceType type : ResourceType.values()) {
            long limit = limits == null ? -1L : limits.getOrDefault(type, -1L);
            if (limit < -1L) {
                throw new IllegalArgumentException("Resource limit must be non-negative or -1");
            }
            copy.put(type, limit);
        }
        this.limits = Map.copyOf(copy);
    }

    public long limitBytes(ResourceType type) {
        return limits.get(type);
    }
}
