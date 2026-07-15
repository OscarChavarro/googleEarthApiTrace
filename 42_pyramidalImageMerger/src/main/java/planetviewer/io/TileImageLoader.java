package planetviewer.io;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import planetviewer.config.Configuration;

/**
 * Background tile PNG decoding, so the GL thread never blocks on disk I/O:
 * decode happens on a small worker pool into a bounded RAM cache of
 * BufferedImages, and the renderer (GL thread) later uploads whatever is
 * ready to the GPU. This is a simplified version of the old prototype's
 * 12-thread producer/consumer pipe design (two worker threads and a plain
 * bounded map are enough for 256x256 PNG tiles).
 */
public final class TileImageLoader {
    private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "planetviewer-tile-loader");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, BufferedImage> ready = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> generations = new ConcurrentHashMap<>();
    private final ArrayDeque<String> readyFifo = new ArrayDeque<>();
    private long readyBytes;
    private volatile Runnable onTileReady;

    public void setOnTileReady(Runnable onTileReady) {
        this.onTileReady = onTileReady;
    }

    public void requestLoad(File tileFile) {
        String path = tileFile.getAbsolutePath();
        if (ready.containsKey(path) || !pending.add(path)) {
            return;
        }
        long generation = generations.getOrDefault(path, 0L);
        pool.submit(() -> {
            try {
                BufferedImage image = ImageIO.read(tileFile);
                if (image != null && generations.getOrDefault(path, 0L) == generation) {
                    registerReady(path, image);
                    Runnable callback = onTileReady;
                    if (callback != null) {
                        callback.run();
                    }
                }
            }
            catch (IOException ignored) {
                // Leave the tile unavailable; the renderer keeps using the ancestor fallback.
            }
            finally {
                pending.remove(path);
            }
        });
    }

    public void invalidate(File tileFile) {
        String path = tileFile.getAbsolutePath();
        generations.merge(path, 1L, Long::sum);
        BufferedImage image = ready.remove(path);
        if (image != null) {
            synchronized (readyFifo) {
                readyFifo.remove(path);
                readyBytes -= estimateBytes(image);
            }
        }
    }

    /** Removes and returns the decoded image for this file, if ready (consumed once uploaded to GPU). */
    public BufferedImage takeReady(File tileFile) {
        String path = tileFile.getAbsolutePath();
        BufferedImage image = ready.remove(path);
        if (image != null) {
            synchronized (readyFifo) {
                readyFifo.remove(path);
                readyBytes -= estimateBytes(image);
            }
        }
        return image;
    }

    private void registerReady(String path, BufferedImage image) {
        synchronized (readyFifo) {
            ready.put(path, image);
            readyFifo.addLast(path);
            readyBytes += estimateBytes(image);
            while (readyBytes > Configuration.MAX_RAM_TILE_CACHE_BYTES && !readyFifo.isEmpty()) {
                String oldest = readyFifo.pollFirst();
                BufferedImage evicted = ready.remove(oldest);
                if (evicted != null) {
                    readyBytes -= estimateBytes(evicted);
                }
            }
        }
    }

    private static long estimateBytes(BufferedImage image) {
        return (long) image.getWidth() * image.getHeight() * 4L;
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
