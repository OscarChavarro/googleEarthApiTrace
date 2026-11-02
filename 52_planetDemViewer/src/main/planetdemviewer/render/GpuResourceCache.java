package planetdemviewer.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Access-ordered LRU of GPU resources bounded by a byte budget. It only decides
 * what stays resident: resources it evicts are returned, and whoever owns the
 * graphics context destroys them.
 */
public final class GpuResourceCache<K, R> implements GpuResourceUsage {
    private final LinkedHashMap<K, R> resources = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<K, Long> bytesByKey = new HashMap<>();
    private final long budgetBytes;
    private final int minimumResident;
    private long bytesAssigned;

    public GpuResourceCache(long budgetBytes, int minimumResident) {
        this.budgetBytes = budgetBytes;
        this.minimumResident = minimumResident;
    }

    /** Does not count as a use of the resource. */
    public boolean contains(K key) {
        return resources.containsKey(key);
    }

    /** Counts as a use of the resource, which renews it in the LRU. */
    public R get(K key) {
        return resources.get(key);
    }

    /** Returns the resources evicted to stay within the budget, least recently used first. */
    public List<R> put(K key, R resource, long bytes) {
        resources.put(key, resource);
        bytesByKey.put(key, bytes);
        bytesAssigned += bytes;
        List<R> evicted = new ArrayList<>();
        Iterator<Map.Entry<K, R>> iterator = resources.entrySet().iterator();
        while (bytesAssigned > budgetBytes && resources.size() > minimumResident && iterator.hasNext()) {
            Map.Entry<K, R> oldest = iterator.next();
            evicted.add(oldest.getValue());
            iterator.remove();
            forgetBytes(oldest.getKey());
        }
        return evicted;
    }

    /** Removes and returns the resources whose keys match. */
    public List<R> removeIf(Predicate<K> keys) {
        List<R> removed = new ArrayList<>();
        Iterator<Map.Entry<K, R>> iterator = resources.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, R> entry = iterator.next();
            if (keys.test(entry.getKey())) {
                removed.add(entry.getValue());
                iterator.remove();
                forgetBytes(entry.getKey());
            }
        }
        return removed;
    }

    /** Removes and returns every resource. */
    public List<R> clear() {
        List<R> removed = new ArrayList<>(resources.values());
        resources.clear();
        bytesByKey.clear();
        bytesAssigned = 0L;
        return removed;
    }

    @Override
    public int residentCount() {
        return resources.size();
    }

    @Override
    public long bytesAssigned() {
        return bytesAssigned;
    }

    private void forgetBytes(K key) {
        Long bytes = bytesByKey.remove(key);
        if (bytes != null) {
            bytesAssigned = Math.max(0L, bytesAssigned - bytes);
        }
    }
}
