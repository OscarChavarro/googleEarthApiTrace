package planetdemviewer.scheduler;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrent work queue for view-driven resources. Higher priority wins and,
 * for equal priority, the newest submission wins. A submission with the same
 * key replaces an in-queue predecessor; this intentionally is not FIFO.
 *
 * <p>The queue is backend- and payload-agnostic. I/O, mesh preparation, and
 * future texture/vector schedulers share this ordering without depending on
 * one another.</p>
 */
public final class LatestRequestQueue<K, V> {
    private final PriorityBlockingQueue<QueuedRequest<K, V>> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<K, QueuedRequest<K, V>> newestByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** Publishes work, replacing any still-queued work with the same key. */
    public void submit(K key, V value, int priority) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        QueuedRequest<K, V> request = new QueuedRequest<>(key, value, priority,
            System.nanoTime(), sequence.incrementAndGet());
        QueuedRequest<K, V> previous = newestByKey.put(key, request);
        if (previous != null) {
            queue.remove(previous);
        }
        queue.offer(request);
    }

    /** Waits for the highest-priority current request. */
    public V take() throws InterruptedException {
        while (true) {
            QueuedRequest<K, V> request = queue.take();
            if (newestByKey.remove(request.key(), request)) {
                return request.value();
            }
        }
    }

    public int size() {
        return newestByKey.size();
    }

    public void clear() {
        newestByKey.clear();
        queue.clear();
    }

    private record QueuedRequest<K, V>(K key, V value, int priority,
                                       long requestedAtNanos, long sequence)
        implements Comparable<QueuedRequest<K, V>> {
        @Override
        public int compareTo(QueuedRequest<K, V> other) {
            int priorityOrder = Integer.compare(other.priority, priority);
            if (priorityOrder != 0) {
                return priorityOrder;
            }
            int timeOrder = Long.compare(other.requestedAtNanos, requestedAtNanos);
            return timeOrder != 0 ? timeOrder : Long.compare(other.sequence, sequence);
        }
    }
}
