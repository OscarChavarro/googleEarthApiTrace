package planetdemviewer.io.imagery;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import planetdemviewer.scheduler.LatestRequestQueue;
import planetdemviewer.spatial.TileAddress;

/** Dedicated PNG decoding thread; imagery remains completely idle until requested. */
public final class ImageryTileLoader {
    private final ImageryTileResolver resolver;
    private final LatestRequestQueue<String, Request> requests = new LatestRequestQueue<>();
    private final ConcurrentHashMap<String, BufferedImage> ready = new ConcurrentHashMap<>();
    private volatile Runnable onReady;
    private volatile boolean running = true;

    public ImageryTileLoader(Path root) {
        resolver = new ImageryTileResolver(root);
        Thread thread = new Thread(this::work, "planet-imagery-png-reader");
        thread.setDaemon(true); thread.start();
    }
    public void setOnReady(Runnable callback) { onReady = callback; }
    public void request(TileAddress address) {
        resolver.resolve(address).ifPresent(path -> requests.submit(path.toString(), new Request(address, path), 100));
    }
    public BufferedImage takeReady(TileAddress address) {
        return resolver.resolve(address).map(path -> ready.remove(path.toString())).orElse(null);
    }

    /**
     * Deletes only the resolved imagery page for an address. Elevation pages
     * are owned by a different loader and are deliberately never touched.
     *
     * @return the deleted PNG path, or empty when that imagery tile is absent
     */
    public java.util.Optional<Path> delete(TileAddress address) throws IOException {
        java.util.Optional<Path> resolved = resolver.resolve(address);
        if (resolved.isEmpty()) {
            return java.util.Optional.empty();
        }
        Path path = resolved.get();
        Files.delete(path);
        ready.remove(path.toString());
        return java.util.Optional.of(path);
    }
    private void work() {
        while (running) try {
            Request request = requests.take();
            BufferedImage image = ImageIO.read(request.path.toFile());
            if (image != null) { ready.put(request.path.toString(), image); Runnable callback = onReady; if (callback != null) callback.run(); }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
          catch (IOException ignored) { }
    }
    public void shutdown() { running = false; requests.clear(); ready.clear(); }
    private record Request(TileAddress address, Path path) { }
}
