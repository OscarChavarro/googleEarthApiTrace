package pyramidalimagecoverage.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public PyramidCatalog scan(Path rootFolder) throws IOException {
        PyramidCatalog catalog = scanRoot(rootFolder);
        try (Stream<Path> paths = Files.walk(rootFolder)) {
            paths.filter(Files::isRegularFile)
                .filter(this::isPng)
                .forEach(path -> addIfTile(catalog, path));
        }
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
        return catalog;
    }

    /**
     * Indexes descendant tiles concurrently. The callback is throttled into batches so a large
     * pyramid can progressively refresh Swing without flooding its event queue.
     */
    public void scanRemaining(PyramidCatalog catalog, Runnable progressCallback) throws IOException {
        AtomicInteger added = new AtomicInteger();
        AtomicReference<IOException> failure = new AtomicReference<>();
        ForkJoinPool pool = new ForkJoinPool(SCAN_THREADS);
        try {
            pool.invoke(new DirectoryScanTask(
                catalog.rootFolder(), catalog, progressCallback, added, failure
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
    }

    private boolean addIfTile(PyramidCatalog catalog, Path path) {
        String fileName = path.getFileName().toString();
        String quadKey = fileName.substring(0, fileName.length() - 4);
        if (!quadKey.matches("0[0-3]*")) {
            return false;
        }
        Path relativePath = catalog.rootFolder().relativize(path);
        if (!matchesSupportedLayout(relativePath, quadKey)) {
            return false;
        }
        try {
            return catalog.add(new TileRecord(TileAddress.fromQuadKey(quadKey), path));
        }
        catch (IllegalArgumentException ignored) {
            // A PNG unrelated to the quadtree is not part of the catalog.
            return false;
        }
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
        private final Runnable progressCallback;
        private final AtomicInteger added;
        private final AtomicReference<IOException> failure;

        private DirectoryScanTask(
            Path folder,
            PyramidCatalog catalog,
            Runnable progressCallback,
            AtomicInteger added,
            AtomicReference<IOException> failure
        ) {
            this.folder = folder;
            this.catalog = catalog;
            this.progressCallback = progressCallback;
            this.added = added;
            this.failure = failure;
        }

        @Override
        protected void compute() {
            List<DirectoryScanTask> children = new ArrayList<>();
            try (Stream<Path> entries = Files.list(folder)) {
                entries.forEach(path -> {
                    if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        children.add(new DirectoryScanTask(
                            path, catalog, progressCallback, added, failure
                        ));
                    }
                    else if (Files.isRegularFile(path) && isPng(path)
                        && addIfTile(catalog, path)
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
