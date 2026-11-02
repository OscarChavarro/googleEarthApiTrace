package planetdemviewer.io.imagery;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import planetdemviewer.scheduler.LatestRequestQueue;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBAImageUncompressed;

/**
 * Dedicated PNG decoding thread; imagery remains completely idle until requested. A second
 * thread finds out which blocks the pyramid has, so that the caller never waits for storage.
 */
public final class ImageryTileLoader implements ImageryBlockLinks {
    /** Decoded pages the camera no longer wants must not pile up on the heap. */
    private static final int MAX_READY_IMAGES = 256;
    private static final int MAX_KNOWN_LINKS = 1 << 18;

    private final ImageryTileResolver resolver;
    private final LatestRequestQueue<TileAddress, TileAddress> requests = new LatestRequestQueue<>();
    private final Map<TileAddress, RGBAImageUncompressed> ready = Collections.synchronizedMap(
        new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TileAddress, RGBAImageUncompressed> eldest) {
                return size() > MAX_READY_IMAGES;
            }
        });
    private final LatestRequestQueue<TileAddress, TileAddress> probes = new LatestRequestQueue<>();
    private final Set<TileAddress> pendingProbes = ConcurrentHashMap.newKeySet();
    private final Map<TileAddress, ImageryBlockLink> links = Collections.synchronizedMap(
        new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TileAddress, ImageryBlockLink> eldest) {
                return size() > MAX_KNOWN_LINKS;
            }
        });
    /** Subtrees deleted this session; they stay hidden even while their files are still going away. */
    private final Set<TileAddress> deletedSubtrees = ConcurrentHashMap.newKeySet();
    private final ExecutorService deleter = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "planet-imagery-deleter");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Runnable onReady;
    private volatile TileAddress decoding;
    private volatile boolean running = true;

    public ImageryTileLoader(Path root) {
        resolver = new ImageryTileResolver(root);
        startThread(this::work, "planet-imagery-png-reader");
        startThread(this::probe, "planet-imagery-tree-reader");
    }

    private static void startThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    public void setOnReady(Runnable callback) { onReady = callback; }

    public void request(TileAddress address) {
        if (isDeleted(address) || address.equals(decoding)) return;
        requests.submit(address, address, 100);
    }

    public RGBAImageUncompressed takeReady(TileAddress address) {
        return isDeleted(address) ? null : ready.remove(address);
    }

    @Override
    public ImageryBlockLink link(TileAddress block) {
        if (isDeleted(block)) return ImageryBlockLink.NONE;
        ImageryBlockLink known = links.get(block);
        if (known == null && pendingProbes.add(block)) {
            probes.submit(block, block, 0);
        }
        return known;
    }

    /**
     * Deletes the imagery PNGs of a tile and of all its descendants, on a
     * background thread because a shallow tile can own a very large subtree.
     * The subtree stops being served immediately, before this method returns.
     * Elevation pages are owned by a different loader and are never touched.
     */
    public CompletableFuture<ImageryDeletionReport> deleteSubtree(TileAddress address) {
        deletedSubtrees.add(address);
        synchronized (ready) {
            ready.keySet().removeIf(address::contains);
        }
        synchronized (links) {
            links.keySet().removeIf(address::contains);
        }
        // An ancestor that only had this subtree below it may have no children now.
        for (TileAddress ancestor = address.parent(); ancestor != null; ancestor = ancestor.parent()) {
            links.remove(ancestor);
        }
        return CompletableFuture.supplyAsync(() -> resolver.tileDirectory(address)
            .map(directory -> ImageryPyramidPruner.prune(directory, address))
            .orElse(ImageryDeletionReport.NOTHING), deleter);
    }

    private boolean isDeleted(TileAddress address) {
        return deletedSubtrees.stream().anyMatch(deleted -> deleted.contains(address));
    }

    private void work() {
        while (running) try {
            TileAddress address = requests.take();
            if (isDeleted(address)) continue;
            decoding = address;
            Path path = resolver.resolve(address).orElse(null);
            RGBAImageUncompressed image = path == null ? null : ImagePersistence.importRGBA(path.toFile());
            if (image != null) {
                ready.put(address, image);
                // Pairs with deleteSubtree's add-then-purge: one of the two sees the other.
                if (isDeleted(address)) { ready.remove(address); continue; }
                Runnable callback = onReady; if (callback != null) callback.run();
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
          catch (Exception ignored) { }
          finally { decoding = null; }
    }

    private void probe() {
        while (running) try {
            TileAddress block = probes.take();
            links.put(block, resolver.link(block));
            pendingProbes.remove(block);
            Runnable callback = onReady; if (callback != null) callback.run();
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
          catch (Exception ignored) { }
    }

    public void shutdown() {
        running = false;
        requests.clear();
        probes.clear();
        ready.clear();
        deleter.shutdown();
    }
}
