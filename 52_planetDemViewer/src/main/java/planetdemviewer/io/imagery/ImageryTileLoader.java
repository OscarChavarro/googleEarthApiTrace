package planetdemviewer.io.imagery;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import planetdemviewer.scheduler.LatestRequestQueue;
import planetdemviewer.spatial.TileAddress;

/** Dedicated PNG decoding thread; imagery remains completely idle until requested. */
public final class ImageryTileLoader {
    private final ImageryTileResolver resolver;
    private final LatestRequestQueue<String, Request> requests = new LatestRequestQueue<>();
    private final ConcurrentHashMap<TileAddress, BufferedImage> ready = new ConcurrentHashMap<>();
    /** Subtrees deleted this session; they stay hidden even while their files are still going away. */
    private final Set<TileAddress> deletedSubtrees = ConcurrentHashMap.newKeySet();
    private final ExecutorService deleter = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "planet-imagery-deleter");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Runnable onReady;
    private volatile boolean running = true;

    public ImageryTileLoader(Path root) {
        resolver = new ImageryTileResolver(root);
        Thread thread = new Thread(this::work, "planet-imagery-png-reader");
        thread.setDaemon(true); thread.start();
    }
    public void setOnReady(Runnable callback) { onReady = callback; }
    public void request(TileAddress address) {
        if (isDeleted(address)) return;
        resolver.resolve(address).ifPresent(path -> requests.submit(path.toString(), new Request(address, path), 100));
    }
    public BufferedImage takeReady(TileAddress address) {
        return isDeleted(address) ? null : ready.remove(address);
    }

    /**
     * Deletes the imagery PNGs of a tile and of all its descendants, on a
     * background thread because a shallow tile can own a very large subtree.
     * The subtree stops being served immediately, before this method returns.
     * Elevation pages are owned by a different loader and are never touched.
     */
    public CompletableFuture<ImageryDeletionReport> deleteSubtree(TileAddress address) {
        deletedSubtrees.add(address);
        ready.keySet().removeIf(address::contains);
        return CompletableFuture.supplyAsync(() -> resolver.tileDirectory(address)
            .map(directory -> ImageryPyramidPruner.prune(directory, address))
            .orElse(ImageryDeletionReport.NOTHING), deleter);
    }
    private boolean isDeleted(TileAddress address) {
        return deletedSubtrees.stream().anyMatch(deleted -> deleted.contains(address));
    }
    private void work() {
        while (running) try {
            Request request = requests.take();
            if (isDeleted(request.address)) continue;
            BufferedImage image = ImageIO.read(request.path.toFile());
            if (image != null) {
                ready.put(request.address, image);
                // Pairs with deleteSubtree's add-then-purge: one of the two sees the other.
                if (isDeleted(request.address)) { ready.remove(request.address); continue; }
                Runnable callback = onReady; if (callback != null) callback.run();
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
          catch (IOException ignored) { }
    }
    public void shutdown() { running = false; requests.clear(); ready.clear(); deleter.shutdown(); }
    private record Request(TileAddress address, Path path) { }
}
