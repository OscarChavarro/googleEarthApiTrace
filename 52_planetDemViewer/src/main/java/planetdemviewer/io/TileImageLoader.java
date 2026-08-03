package planetdemviewer.io;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import planetdemviewer.config.Configuration;
import planetdemviewer.config.StorageProfile;
import planetdemviewer.model.DemTile;
import planetdemviewer.palette.PaletteCatalog;

/**
 * Asynchronously reads complete 258x258 DEMs, retains their halos in a bounded
 * elevation cache, and derives transient 256x256 palette images for JOGL.
 */
public final class TileImageLoader {
    private final ExecutorService pool;
    private final PaletteCatalog palettes;
    private final Set<String> pendingImages = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingElevations = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, DemTile> elevations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedImage> readyImages = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();
    private final ArrayDeque<String> elevationFifo = new ArrayDeque<>();
    private final ArrayDeque<String> imageFifo = new ArrayDeque<>();
    private long elevationBytes;
    private long imageBytes;
    private volatile long paletteGeneration;
    private volatile Runnable onTileReady;
    private final long ramBudgetBytes;

    public TileImageLoader(PaletteCatalog palettes) {
        this(palettes, StorageProfile.SLOW);
    }

    public TileImageLoader(PaletteCatalog palettes, StorageProfile storageProfile) {
        this.palettes = palettes;
        StorageProfile effectiveProfile = storageProfile == null ? StorageProfile.SLOW : storageProfile;
        this.pool = Executors.newFixedThreadPool(effectiveProfile.tileLoaderThreads(), runnable -> {
            Thread thread = new Thread(runnable, "planet-dem-tile-loader");
            thread.setDaemon(true);
            return thread;
        });
        this.ramBudgetBytes = Configuration.maxRamTileCacheBytes();
    }

    public void setOnTileReady(Runnable onTileReady) {
        this.onTileReady = onTileReady;
    }

    public void requestLoad(File tileFile) {
        String path = tileFile.getAbsolutePath();
        if (readyImages.containsKey(path) || !pendingImages.add(path)) {
            return;
        }
        long requestedGeneration = paletteGeneration;
        pool.submit(() -> {
            try {
                DemTile tile = getOrRead(tileFile);
                BufferedImage image = tile.colorizeCore(palettes);
                if (requestedGeneration == paletteGeneration) {
                    registerReadyImage(path, image);
                    Runnable callback = onTileReady;
                    if (callback != null) {
                        callback.run();
                    }
                }
            }
            catch (IOException ignored) {
                // The renderer continues with the nearest available ancestor.
            }
            finally {
                pendingImages.remove(path);
            }
        });
    }

    /** Requests only the halo-preserving DEM data needed by terrain modes. */
    public void requestElevation(File tileFile) {
        String path = tileFile.getAbsolutePath();
        if (elevations.containsKey(path) || !pendingElevations.add(path)) {
            return;
        }
        pool.submit(() -> {
            try {
                getOrRead(tileFile);
                Runnable callback = onTileReady;
                if (callback != null) {
                    callback.run();
                }
            }
            catch (IOException ignored) {
                // A sparse or damaged tile is simply unavailable to the renderer.
            }
            finally {
                pendingElevations.remove(path);
            }
        });
    }

    public DemTile peekElevation(File tileFile) {
        return elevations.get(tileFile.getAbsolutePath());
    }

    public BufferedImage loadSynchronously(File tileFile) throws IOException {
        return getOrRead(tileFile).colorizeCore(palettes);
    }

    /** Exposes the full halo-preserving data path to a future TerrainMeshGenerator. */
    public DemTile getElevationTile(File tileFile) throws IOException {
        return getOrRead(tileFile);
    }

    public BufferedImage takeReady(File tileFile) {
        String path = tileFile.getAbsolutePath();
        BufferedImage image = readyImages.remove(path);
        if (image != null) {
            synchronized (cacheLock) {
                imageFifo.remove(path);
                imageBytes -= imageBytes(image);
            }
        }
        return image;
    }

    /** Retains cached elevations but discards every color image from the old palette. */
    public void paletteChanged() {
        paletteGeneration++;
        synchronized (cacheLock) {
            readyImages.clear();
            imageFifo.clear();
            imageBytes = 0L;
        }
    }

    private DemTile getOrRead(File tileFile) throws IOException {
        String path = tileFile.getAbsolutePath();
        DemTile cached = elevations.get(path);
        if (cached != null) {
            return cached;
        }
        DemTile loaded = DemTile.read(tileFile.toPath());
        synchronized (cacheLock) {
            DemTile raced = elevations.putIfAbsent(path, loaded);
            if (raced != null) {
                return raced;
            }
            elevationFifo.addLast(path);
            elevationBytes += DemTile.BYTE_COUNT;
            enforceRamBudget();
        }
        return loaded;
    }

    private void registerReadyImage(String path, BufferedImage image) {
        synchronized (cacheLock) {
            BufferedImage old = readyImages.put(path, image);
            if (old != null) {
                imageFifo.remove(path);
                imageBytes -= imageBytes(old);
            }
            imageFifo.addLast(path);
            imageBytes += imageBytes(image);
            enforceRamBudget();
        }
    }

    private void enforceRamBudget() {
        while (elevationBytes + imageBytes > ramBudgetBytes) {
            if (!imageFifo.isEmpty()) {
                String oldestImage = imageFifo.pollFirst();
                BufferedImage evicted = readyImages.remove(oldestImage);
                if (evicted != null) {
                    imageBytes -= imageBytes(evicted);
                }
            }
            else if (!elevationFifo.isEmpty()) {
                String oldestElevation = elevationFifo.pollFirst();
                if (elevations.remove(oldestElevation) != null) {
                    elevationBytes -= DemTile.BYTE_COUNT;
                }
            }
            else {
                break;
            }
        }
    }

    private static long imageBytes(BufferedImage image) {
        return (long) image.getWidth() * image.getHeight() * Integer.BYTES;
    }

    public void shutdown() {
        pool.shutdownNow();
        elevations.clear();
        readyImages.clear();
    }

    public long getRamBudgetBytes() {
        return ramBudgetBytes;
    }
}
