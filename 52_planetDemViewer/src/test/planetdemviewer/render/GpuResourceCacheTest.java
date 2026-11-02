package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GpuResourceCacheTest {
    @Test
    void returnsTheLeastRecentlyUsedResourcesOverTheBudget() {
        GpuResourceCache<String, String> cache = new GpuResourceCache<>(20, 0);
        cache.put("a", "A", 10);
        cache.put("b", "B", 10);
        cache.get("a");
        assertEquals(List.of("B"), cache.put("c", "C", 10));
        assertEquals(20, cache.bytesAssigned());
    }

    @Test
    void askingWhetherAResourceIsResidentDoesNotRenewIt() {
        GpuResourceCache<String, String> cache = new GpuResourceCache<>(20, 0);
        cache.put("a", "A", 10);
        cache.put("b", "B", 10);
        assertTrue(cache.contains("a"));
        assertEquals(List.of("A"), cache.put("c", "C", 10));
        assertFalse(cache.contains("a"));
    }

    @Test
    void keepsTheMinimumResidentEvenOverTheBudget() {
        GpuResourceCache<String, String> cache = new GpuResourceCache<>(5, 1);
        assertEquals(List.of(), cache.put("a", "A", 10));
        assertEquals(List.of("A"), cache.put("b", "B", 10));
        assertEquals(1, cache.residentCount());
    }

    @Test
    void removalsReturnWhatTheBackendMustDestroy() {
        GpuResourceCache<String, String> cache = new GpuResourceCache<>(100, 0);
        cache.put("a", "A", 10);
        cache.put("ab", "AB", 10);
        cache.put("b", "B", 10);
        assertEquals(List.of("A", "AB"), cache.removeIf(key -> key.startsWith("a")));
        assertEquals(10, cache.bytesAssigned());
        assertEquals(List.of("B"), cache.clear());
        assertEquals(0, cache.bytesAssigned());
    }
}
