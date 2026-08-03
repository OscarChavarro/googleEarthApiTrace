package planetdemviewer.io;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import planetdemviewer.config.StorageProfile;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.model.QuadtreeNode;

/**
 * Shared, priority-ordered metadata crawler. It never opens a .bin file and
 * each directory node is inspected at most once during the session.
 */
public final class TileTreeDiscoveryService {
    private static final int VISIBLE_PRIORITY = 0;
    private static final int LOOK_AHEAD_PRIORITY = 10;
    private static final int NEIGHBOUR_PRIORITY = 20;

    private final StorageProfile profile;
    private final PriorityBlockingQueue<Request> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<QuadtreeNode, Request> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Thread[] workers;
    private volatile boolean running = true;
    private volatile Runnable onTreeChanged;

    public TileTreeDiscoveryService(StorageProfile profile) {
        this.profile = profile == null ? StorageProfile.SLOW : profile;
        workers = new Thread[this.profile.discoveryThreads()];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(this::work, "planet-dem-tree-discovery-" + i);
            workers[i].setDaemon(true);
            workers[i].setPriority(Thread.MIN_PRIORITY);
            workers[i].start();
        }
    }

    public void setOnTreeChanged(Runnable onTreeChanged) {
        this.onTreeChanged = onTreeChanged;
    }

    /** Prioritizes the visible node, one look-ahead level, then its siblings. */
    public void requestVisible(PyramidalImage image, QuadtreeNode node) {
        if (image == null || node == null || node.getContainerDirectory() == null) {
            return;
        }
        if (node.isDiscoveryComplete()) {
            QuadtreeNode[] children = node.getChildren();
            if (children != null) {
                for (QuadtreeNode child : children) {
                    if (child != null) {
                        enqueue(image, child, VISIBLE_PRIORITY, Math.max(0, profile.discoveryLookAhead() - 1));
                    }
                }
            }
        }
        else {
            enqueue(image, node, VISIBLE_PRIORITY, profile.discoveryLookAhead());
        }
        QuadtreeNode parent = node.getParent();
        if (parent == null || !parent.isDiscoveryComplete()) {
            return;
        }
        QuadtreeNode[] siblings = parent.getChildren();
        if (siblings == null) {
            return;
        }
        for (QuadtreeNode sibling : siblings) {
            if (sibling != null && sibling != node) {
                enqueue(image, sibling, NEIGHBOUR_PRIORITY, 0);
            }
        }
    }

    public int getPendingCount() {
        return queue.size();
    }

    private void enqueue(PyramidalImage image, QuadtreeNode node, int priority, int lookAhead) {
        if (!running) {
            return;
        }
        synchronized (node) {
            Request old = pending.get(node);
            if (old != null) {
                if (priority >= old.priority && lookAhead <= old.lookAhead) {
                    return;
                }
                Request upgraded = new Request(
                    image,
                    node,
                    Math.min(priority, old.priority),
                    Math.max(lookAhead, old.lookAhead),
                    old.sequence
                );
                if (queue.remove(old) && pending.replace(node, old, upgraded)) {
                    queue.offer(upgraded);
                }
                return;
            }
            if (!node.queueDiscovery()) {
                return;
            }
            Request request = new Request(image, node, priority, lookAhead, sequence.getAndIncrement());
            pending.put(node, request);
            queue.offer(request);
        }
    }

    private void work() {
        while (running) {
            try {
                Request request = queue.take();
                synchronized (request.node) {
                    pending.remove(request.node, request);
                    if (!request.node.beginDiscovery()) {
                        continue;
                    }
                }
                request.image.discoverChildren(request.node);
                if (request.lookAhead > 0) {
                    QuadtreeNode[] children = request.node.getChildren();
                    if (children != null) {
                        for (QuadtreeNode child : children) {
                            if (child != null) {
                                enqueue(
                                    request.image,
                                    child,
                                    request.priority + LOOK_AHEAD_PRIORITY,
                                    request.lookAhead - 1
                                );
                            }
                        }
                    }
                }
                Runnable callback = onTreeChanged;
                if (callback != null) {
                    callback.run();
                }
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (RuntimeException ignored) {
                // A failed directory is finalized as empty by PyramidalImage;
                // the worker remains available for other visible regions.
            }
        }
    }

    public void shutdown() {
        running = false;
        queue.clear();
        pending.clear();
        for (Thread worker : workers) {
            worker.interrupt();
        }
    }

    private record Request(
        PyramidalImage image,
        QuadtreeNode node,
        int priority,
        int lookAhead,
        long sequence
    ) implements Comparable<Request> {
        @Override
        public int compareTo(Request other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
