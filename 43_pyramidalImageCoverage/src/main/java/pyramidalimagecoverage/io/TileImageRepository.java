package pyramidalimagecoverage.io;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

public final class TileImageRepository {
    private static final int MAX_CACHED_IMAGES = 256;
    private static final int LOADER_THREADS = Math.max(
        2, Math.min(4, Runtime.getRuntime().availableProcessors())
    );

    private final Map<Path, BufferedImage> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, BufferedImage> eldest) {
            return size() > MAX_CACHED_IMAGES;
        }
    };
    private final Set<Path> pending = ConcurrentHashMap.newKeySet();
    private final Set<Path> failed = ConcurrentHashMap.newKeySet();
    private final boolean asynchronous;
    private final ExecutorService loaders;

    /** Creates a deterministic synchronous repository for off-screen rendering and tests. */
    public TileImageRepository() {
        this(false);
    }

    private TileImageRepository(boolean asynchronous) {
        this.asynchronous = asynchronous;
        this.loaders = asynchronous
            ? Executors.newFixedThreadPool(LOADER_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "tile-image-loader");
                thread.setDaemon(true);
                return thread;
            })
            : null;
    }

    /** Creates the non-blocking repository used by the interactive viewer. */
    public static TileImageRepository asynchronous() {
        return new TileImageRepository(true);
    }

    public BufferedImage getOrRequest(Path path, Runnable repaintCallback) {
        if (!asynchronous) {
            return load(path);
        }
        BufferedImage cached = cached(path);
        if (cached != null) {
            return cached;
        }
        if (!failed.contains(path) && pending.add(path)) {
            loaders.execute(() -> {
                try {
                    BufferedImage image = ImageIO.read(path.toFile());
                    if (image != null) {
                        put(path, image);
                    }
                    else {
                        failed.add(path);
                    }
                }
                catch (IOException ex) {
                    failed.add(path);
                    System.err.println(
                        "WARNING: Could not read tile image " + path + ": " + ex.getMessage()
                    );
                }
                finally {
                    pending.remove(path);
                    javax.swing.SwingUtilities.invokeLater(repaintCallback);
                }
            });
        }
        return null;
    }

    public boolean failed(Path path) {
        return failed.contains(path);
    }

    private synchronized BufferedImage cached(Path path) {
        return cache.get(path);
    }

    private synchronized void put(Path path, BufferedImage image) {
        cache.put(path, image);
    }

    /** Synchronous compatibility helper, primarily useful to non-GUI callers. */
    public synchronized BufferedImage load(Path path) {
        BufferedImage image = cache.get(path);
        if (image != null) {
            return image;
        }
        try {
            image = ImageIO.read(path.toFile());
            if (image != null) {
                cache.put(path, image);
            }
            else {
                failed.add(path);
            }
            return image;
        }
        catch (IOException ex) {
            failed.add(path);
            System.err.println("WARNING: Could not read tile image " + path + ": " + ex.getMessage());
            return null;
        }
    }
}
