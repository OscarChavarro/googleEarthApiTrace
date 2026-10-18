package pyramidalimagecoverage.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileRecord;

public final class PyramidalImageScanner {
    private static final int SCAN_THREADS = Math.max(
        2, Math.min(4, Runtime.getRuntime().availableProcessors())
    );
    private static final int PROGRESS_BATCH_SIZE = 256;
    private final Path cacheEligiblePyramid;
    private final Path cacheFolder;
    private final Map<PyramidCatalog, TileExistenceCache> preparedCaches =
        Collections.synchronizedMap(new WeakHashMap<>());

    public PyramidalImageScanner() {
        this(TileExistenceCache.PRODUCTION_PYRAMID, TileExistenceCache.PRODUCTION_CACHE_FOLDER);
    }

    PyramidalImageScanner(Path cacheEligiblePyramid, Path cacheFolder) {
        this.cacheEligiblePyramid = cacheEligiblePyramid;
        this.cacheFolder = cacheFolder;
    }

    public PyramidCatalog scan(Path rootFolder) throws IOException {
        PyramidCatalog catalog = scanRoot(rootFolder);
        scanRemaining(catalog, () -> { });
        return catalog;
    }

    /** Loads only the root tile so the viewer can be displayed without walking the pyramid. */
    public PyramidCatalog scanRoot(Path rootFolder) throws IOException {
        Path normalizedRoot = rootFolder.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IOException("The pyramidal image folder does not exist: " + rootFolder);
        }
        Path rootTile = normalizedRoot.resolve("0.png");
        if (!Files.isRegularFile(rootTile)) {
            throw new IOException("The pyramid does not contain a valid root tile named 0.png");
        }
        PyramidCatalog catalog = new PyramidCatalog(normalizedRoot);
        catalog.add(new TileRecord(TileAddress.fromQuadKey("0"), rootTile));
        TileExistenceCache cache = cacheFor(normalizedRoot);
        if (cache.enabled()) {
            try {
                int loaded = cache.loadInto(catalog);
                if (loaded > 0) {
                    System.out.println("Loaded " + loaded + " known tile(s) from " + cacheFolder);
                }
            }
            catch (IOException ex) {
                System.err.println("WARNING: Could not read tile existence cache: " + ex.getMessage());
            }
        }
        preparedCaches.put(catalog, cache);
        return catalog;
    }

    /**
     * Indexes descendant tiles concurrently. The callback is throttled into batches so a large
     * pyramid can progressively refresh Swing without flooding its event queue.
     */
    public void scanRemaining(PyramidCatalog catalog, Runnable progressCallback) throws IOException {
        AtomicInteger added = new AtomicInteger();
        AtomicReference<IOException> failure = new AtomicReference<>();
        TileExistenceCache preparedCache = preparedCaches.remove(catalog);
        TileExistenceCache cache = preparedCache == null
            ? cacheFor(catalog.rootFolder())
            : preparedCache;
        if (preparedCache == null && cache.enabled()) {
            try {
                cache.loadInto(catalog);
            }
            catch (IOException ex) {
                System.err.println("WARNING: Could not read tile existence cache: " + ex.getMessage());
            }
        }
        TileAddress rootAddress = TileAddress.fromQuadKey("0");
        if (!cache.contains(rootAddress)) {
            cache.remember(rootAddress);
        }
        ForkJoinPool pool = new ForkJoinPool(SCAN_THREADS);
        try {
            pool.invoke(new DirectoryScanTask(
                catalog.rootFolder(), catalog, cache, progressCallback, added, failure
            ));
        }
        finally {
            pool.shutdown();
        }
        progressCallback.run();
        IOException exception = failure.get();
        if (exception != null) {
            throw exception;
        }
        try {
            cache.flush();
        }
        catch (IOException ex) {
            System.err.println("WARNING: Could not write tile existence cache: " + ex.getMessage());
        }
    }

    /** Adds tiles published after initial indexing at one requested depth. */
    public void scanLevel(PyramidCatalog catalog, int depth, Runnable progressCallback) throws IOException {
        if (catalog == null || depth < 0 || depth > PyramidCatalog.MAX_ADDRESSABLE_DEPTH) {
            return;
        }
        TileExistenceCache cache = cacheFor(catalog.rootFolder());
        AtomicInteger added = new AtomicInteger();
        try (Stream<Path> paths = Files.walk(catalog.rootFolder(), depth + 1)) {
            paths.filter(Files::isRegularFile).filter(this::isPng).forEach(path -> {
                TileAddress address = tileAddress(catalog, path);
                if (address != null && address.depth() == depth && addIfTile(catalog, cache, path)) {
                    added.incrementAndGet();
                }
            });
        }
        if (added.get() > 0) {
            cache.flush();
            progressCallback.run();
        }
    }

    private boolean addIfTile(PyramidCatalog catalog, TileExistenceCache cache, Path path) {
        TileAddress address = tileAddress(catalog, path);
        if (address == null) {
            return false;
        }
        boolean added = catalog.add(new TileRecord(address, path));
        if (added) {
            cache.remember(address);
        }
        return added;
    }

    private TileAddress tileAddress(PyramidCatalog catalog, Path path) {
        String fileName = path.getFileName().toString();
        String quadKey = fileName.substring(0, fileName.length() - 4);
        if (!quadKey.matches("0[0-3]*")) {
            return null;
        }
        Path relativePath = catalog.rootFolder().relativize(path);
        if (!matchesSupportedLayout(relativePath, quadKey)) {
            return null;
        }
        try {
            return TileAddress.fromQuadKey(quadKey);
        }
        catch (IllegalArgumentException ignored) {
            // A PNG unrelated to the quadtree is not part of the catalog.
            return null;
        }
    }

    private TileExistenceCache cacheFor(Path rootFolder) {
        return new TileExistenceCache(rootFolder, cacheEligiblePyramid, cacheFolder);
    }

    private boolean isPng(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private boolean matchesSupportedLayout(Path relativePath, String quadKey) {
        return relativePath.equals(relativePathForDigitFolders(quadKey))
            || relativePath.equals(relativePathForLegacyCumulativeFolders(quadKey));
    }

    private Path relativePathForDigitFolders(String quadKey) {
        Path path = Path.of(quadKey + ".png");
        for (int index = quadKey.length() - 1; index >= 1; index--) {
            path = Path.of(String.valueOf(quadKey.charAt(index)), path.toString());
        }
        return path;
    }

    private Path relativePathForLegacyCumulativeFolders(String quadKey) {
        Path path = Path.of(quadKey + ".png");
        for (int length = quadKey.length(); length >= 2; length--) {
            path = Path.of(quadKey.substring(0, length), path.toString());
        }
        return path;
    }

    private final class DirectoryScanTask extends RecursiveAction {
        private final Path folder;
        private final PyramidCatalog catalog;
        private final TileExistenceCache cache;
        private final Runnable progressCallback;
        private final AtomicInteger added;
        private final AtomicReference<IOException> failure;

        private DirectoryScanTask(
            Path folder,
            PyramidCatalog catalog,
            TileExistenceCache cache,
            Runnable progressCallback,
            AtomicInteger added,
            AtomicReference<IOException> failure
        ) {
            this.folder = folder;
            this.catalog = catalog;
            this.cache = cache;
            this.progressCallback = progressCallback;
            this.added = added;
            this.failure = failure;
        }

        @Override
        protected void compute() {
            List<DirectoryScanTask> children = new ArrayList<>();
            try (Stream<Path> entries = Files.list(folder)) {
                entries.forEach(path -> {
                    if (cache.enabled() && isPng(path)) {
                        TileAddress address = tileAddress(catalog, path);
                        boolean known = address != null && cache.contains(address);
                        if (address != null && (known || Files.isRegularFile(path))
                            && addIfTile(catalog, cache, path)
                            && added.incrementAndGet() % PROGRESS_BATCH_SIZE == 0) {
                            progressCallback.run();
                        }
                    }
                    else if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        children.add(new DirectoryScanTask(
                            path, catalog, cache, progressCallback, added, failure
                        ));
                    }
                    else if (Files.isRegularFile(path) && isPng(path)
                        && addIfTile(catalog, cache, path)
                        && added.incrementAndGet() % PROGRESS_BATCH_SIZE == 0) {
                        progressCallback.run();
                    }
                });
            }
            catch (IOException ex) {
                failure.compareAndSet(null, ex);
            }
            invokeAll(children);
        }
    }
}
