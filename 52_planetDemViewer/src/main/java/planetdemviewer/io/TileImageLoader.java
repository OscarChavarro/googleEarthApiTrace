package planetdemviewer.io;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import planetdemviewer.config.Configuration;
import planetdemviewer.config.StorageProfile;
import planetdemviewer.io.elevation.FabdemElevationNormalizer;
import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.model.DemTile;
import planetdemviewer.palette.PaletteCatalog;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.scheduler.LatestRequestQueue;
import planetdemviewer.scheduler.ResourceTelemetry;
import planetdemviewer.scheduler.ResourceType;

/**
 * Asynchronously reads complete 258x258 DEMs, retains their halos in a bounded
 * LRU cache, and derives transient 256x256 palette images for JOGL. Every
 * successful cache lookup renews the page's access time.
 */
public final class TileImageLoader implements planetdemviewer.io.elevation.ElevationTileSource {
    /** Last requested visible tile is read first; this is deliberately not FIFO. */
    private static final int VISIBLE_TILE_PRIORITY = 100;
    private final LatestRequestQueue<RequestKey, LoadRequest> requests = new LatestRequestQueue<>();
    private final Thread[] readers;
    private volatile boolean running = true;
    private final PaletteCatalog palettes;
    private final FabdemElevationNormalizer elevationNormalizer;
    private final ConcurrentHashMap<String, DemTile> elevations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<DemTile>> pendingElevationRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedImage> readyImages = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();
    /** One LRU order across source DEM pages and their transient colour pages. */
    private final LinkedHashMap<CacheKey, Long> residentLru = new LinkedHashMap<>(256, 0.75f, true);
    private long elevationBytes;
    private long imageBytes;
    private volatile long paletteGeneration;
    private volatile Runnable onTileReady;
    private final long ramBudgetBytes;
    private final ResourceTelemetry resourceTelemetry;

    public TileImageLoader(PaletteCatalog palettes) {
        this(palettes, StorageProfile.SLOW);
    }

    public TileImageLoader(PaletteCatalog palettes, StorageProfile storageProfile) {
        this(palettes, storageProfile, new ResourceTelemetry());
    }

    public TileImageLoader(PaletteCatalog palettes, StorageProfile storageProfile,
                           ResourceTelemetry resourceTelemetry) {
        this.palettes = palettes;
        this.resourceTelemetry = resourceTelemetry == null ? new ResourceTelemetry() : resourceTelemetry;
        try {
            this.elevationNormalizer = FabdemElevationNormalizer.load(Configuration.EGM2008_GRID);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Cannot load required EGM2008 grid: "
                + Configuration.EGM2008_GRID.toAbsolutePath(), exception);
        }
        StorageProfile effectiveProfile = storageProfile == null ? StorageProfile.SLOW : storageProfile;
        readers = new Thread[effectiveProfile.tileLoaderThreads()];
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(this::readTiles, "planet-dem-bin-reader-" + i);
            readers[i].setDaemon(true);
            readers[i].start();
        }
        this.ramBudgetBytes = Configuration.maxRamTileCacheBytes();
    }

    public void setOnTileReady(Runnable onTileReady) {
        this.onTileReady = onTileReady;
    }

    public void requestLoad(File tileFile) {
        if (tileFile == null || readyImages.containsKey(tileFile.getAbsolutePath())) {
            return;
        }
        enqueue(tileFile, RequestKind.IMAGE, paletteGeneration);
    }

    /** Requests only the halo-preserving DEM data needed by terrain modes. */
    @Override
    public void requestElevation(File tileFile) {
        requestElevationAsync(tileFile);
    }

    /** Asynchronous address-adapter entry point; loading remains owned by this source executor. */
    public CompletionStage<DemTile> requestElevationAsync(File tileFile) {
        if (tileFile == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tile file is required"));
        }
        DemTile cached = peekElevation(tileFile);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        String path = tileFile.getAbsolutePath();
        CompletableFuture<DemTile> created = new CompletableFuture<>();
        CompletableFuture<DemTile> current = pendingElevationRequests.putIfAbsent(path, created);
        if (current != null) {
            return current;
        }
        created.whenComplete((ignored, error) -> pendingElevationRequests.remove(path, created));
        enqueue(tileFile, RequestKind.ELEVATION, paletteGeneration);
        return created;
    }

    private void enqueue(File tileFile, RequestKind kind, long generation) {
        if (!running) {
            return;
        }
        RequestKey key = new RequestKey(kind, tileFile.getAbsolutePath());
        requests.submit(key, new LoadRequest(key, tileFile, generation), VISIBLE_TILE_PRIORITY);
    }

    private void readTiles() {
        while (running) {
            LoadRequest request = null;
            try {
                request = requests.take();
                DemTile tile = getOrRead(request.file);
                if (request.key.kind == RequestKind.ELEVATION) {
                    completeElevationRequest(request.key.path, tile);
                }
                if (request.key.kind == RequestKind.IMAGE && request.generation == paletteGeneration) {
                    registerReadyImage(request.key.path, tile.colorizeCore(palettes));
                }
                Runnable callback = onTileReady;
                if (callback != null) {
                    callback.run();
                }
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (IOException exception) {
                if (request != null && request.key.kind == RequestKind.ELEVATION) {
                    failElevationRequest(request.key.path, exception);
                }
                // A sparse or damaged tile is simply unavailable to the renderer.
            }
        }
    }

    @Override
    public DemTile peekElevation(File tileFile) {
        String path = tileFile.getAbsolutePath();
        synchronized (cacheLock) {
            DemTile tile = elevations.get(path);
            if (tile != null) {
                touchLocked(CacheKey.elevation(path));
            }
            return tile;
        }
    }

    public BufferedImage loadSynchronously(File tileFile) throws IOException {
        return getOrRead(tileFile).colorizeCore(palettes);
    }

    /** Exposes the full halo-preserving data path to a future TerrainMeshGenerator. */
    @Override
    public DemTile getElevationTile(File tileFile) throws IOException {
        return getOrRead(tileFile);
    }

    public BufferedImage takeReady(File tileFile) {
        String path = tileFile.getAbsolutePath();
        synchronized (cacheLock) {
            BufferedImage image = readyImages.remove(path);
            if (image != null) {
                residentLru.remove(CacheKey.image(path));
                imageBytes -= imageBytes(image);
                publishResourceUsageLocked();
            }
            return image;
        }
    }

    /** Retains cached elevations but discards every color image from the old palette. */
    public void paletteChanged() {
        paletteGeneration++;
        synchronized (cacheLock) {
            readyImages.clear();
            residentLru.entrySet().removeIf(entry -> entry.getKey().type() == CacheType.IMAGE);
            imageBytes = 0L;
            publishResourceUsageLocked();
        }
    }

    private DemTile getOrRead(File tileFile) throws IOException {
        String path = tileFile.getAbsolutePath();
        synchronized (cacheLock) {
            DemTile cached = elevations.get(path);
            if (cached != null) {
                touchLocked(CacheKey.elevation(path));
                return cached;
            }
        }
        DemTile loaded = DemTile.read(tileFile.toPath(),
            elevationNormalizer.normalizerFor(addressFor(tileFile)));
        synchronized (cacheLock) {
            DemTile raced = elevations.putIfAbsent(path, loaded);
            if (raced != null) {
                touchLocked(CacheKey.elevation(path));
                return raced;
            }
            touchLocked(CacheKey.elevation(path));
            elevationBytes += loaded.retainedBytes();
            enforceRamBudget();
        }
        return loaded;
    }

    private void registerReadyImage(String path, BufferedImage image) {
        synchronized (cacheLock) {
            BufferedImage old = readyImages.put(path, image);
            if (old != null) {
                residentLru.remove(CacheKey.image(path));
                imageBytes -= imageBytes(old);
            }
            touchLocked(CacheKey.image(path));
            imageBytes += imageBytes(image);
            enforceRamBudget();
        }
    }

    private void enforceRamBudget() {
        Iterator<Map.Entry<CacheKey, Long>> iterator = residentLru.entrySet().iterator();
        while (elevationBytes + imageBytes > ramBudgetBytes && iterator.hasNext()) {
            CacheKey oldest = iterator.next().getKey();
            iterator.remove();
            if (oldest.type() == CacheType.ELEVATION) {
                DemTile removed = elevations.remove(oldest.path());
                if (removed != null) {
                    elevationBytes -= removed.retainedBytes();
                }
            }
            else {
                BufferedImage evicted = readyImages.remove(oldest.path());
                if (evicted != null) {
                    imageBytes -= imageBytes(evicted);
                }
            }
        }
        publishResourceUsageLocked();
    }

    /** LinkedHashMap access order selects the least-recently-viewed page first. */
    private void touchLocked(CacheKey key) {
        residentLru.put(key, System.nanoTime());
    }

    private static long imageBytes(BufferedImage image) {
        return (long) image.getWidth() * image.getHeight() * Integer.BYTES;
    }

    private static TileAddress addressFor(File tileFile) throws IOException {
        String name = tileFile.getName();
        if (!name.endsWith(".bin")) {
            throw new IOException("FABDEM tile does not have a .bin quadkey name: " + tileFile);
        }
        try {
            return TileAddress.fabdem(name.substring(0, name.length() - 4));
        }
        catch (IllegalArgumentException exception) {
            throw new IOException("Invalid FABDEM quadkey filename: " + tileFile, exception);
        }
    }

    public void shutdown() {
        running = false;
        for (Thread reader : readers) {
            reader.interrupt();
        }
        requests.clear();
        elevations.clear();
        pendingElevationRequests.forEach((path, request) -> request.cancel(false));
        pendingElevationRequests.clear();
        readyImages.clear();
        synchronized (cacheLock) {
            residentLru.clear();
            elevationBytes = 0L;
            imageBytes = 0L;
            publishResourceUsageLocked();
        }
    }

    public long getRamBudgetBytes() {
        return ramBudgetBytes;
    }

    /** Bytes retained by the DEM pages and their palette images in the tile RAM cache. */
    public long getRamBytesUsed() {
        synchronized (cacheLock) {
            return elevationBytes + imageBytes;
        }
    }

    public ResourceTelemetry resourceTelemetry() {
        return resourceTelemetry;
    }

    /** EGM2008 orthometric height, in metres above mean sea level. */
    public double orthometricHeight(GeographicCoordinate coordinate, double ellipsoidalHeightMetres) {
        return elevationNormalizer.orthometricHeight(coordinate, ellipsoidalHeightMetres);
    }

    private void completeElevationRequest(String path, DemTile tile) {
        CompletableFuture<DemTile> request = pendingElevationRequests.get(path);
        if (request != null) {
            request.complete(tile);
        }
    }

    private void failElevationRequest(String path, IOException exception) {
        CompletableFuture<DemTile> request = pendingElevationRequests.get(path);
        if (request != null) {
            request.completeExceptionally(exception);
        }
    }

    private void publishResourceUsageLocked() {
        resourceTelemetry.setBytes(ResourceType.ELEVATION_RAM, elevationBytes);
        resourceTelemetry.setBytes(ResourceType.RASTER_RAM, imageBytes);
    }

    private enum CacheType {
        ELEVATION,
        IMAGE
    }

    private record CacheKey(CacheType type, String path) {
        private static CacheKey elevation(String path) {
            return new CacheKey(CacheType.ELEVATION, path);
        }

        private static CacheKey image(String path) {
            return new CacheKey(CacheType.IMAGE, path);
        }
    }

    private enum RequestKind { ELEVATION, IMAGE }

    private record RequestKey(RequestKind kind, String path) { }

    private record LoadRequest(RequestKey key, File file, long generation) { }
}
