package frametexturenormalizer.io;

// Java classes
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

// App classes
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.contract.ScopedTileIds;
import frametexturenormalizer.model.state.FrameTextureNormalizerState;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.processing.filtering.TileFiltererByConnectedComponents;
import frametexturenormalizer.processing.filtering.TileFiltererByGeometricNullNeighbors;

public final class FrameJsonReader {
    private FrameJsonReader() {
    }

    public static void loadTracedFrames(
        FrameJsonSessionReader traceSessionReader,
        TileFiltererByConnectedComponents connectedComponentsFilterer,
        TileFiltererByGeometricNullNeighbors tileFilterer,
        FrameTextureNormalizerState model
    ) {
        Runnable reloadTileMatrices = () -> {
            List<FrameData> loaded = readFramesParallel(traceSessionReader);
            List<FrameData> filtered = filterFrames(loaded, connectedComponentsFilterer, tileFilterer, model);
            model.setFrames(filtered);
        };
        reloadTileMatrices.run();
    }

    private static List<FrameData> readFramesParallel(FrameJsonSessionReader traceSessionReader) {
        if (traceSessionReader == null) {
            return List.of();
        }

        List<Path> frameDirs = traceSessionReader.listFrameDirectories(Path.of(Configuration.INPUT_PATH));
        if (frameDirs.isEmpty()) {
            return List.of();
        }

        ConcurrentLinkedQueue<Path> pendingDirs = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<LoadedFrame> loadedFrames = new ConcurrentLinkedQueue<>();
        AtomicBoolean producerDone = new AtomicBoolean(false);

        Thread producer = new Thread(
            () -> produceFrameQueue(frameDirs, pendingDirs, producerDone),
            "frame-reader-producer"
        );
        producer.start();

        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        Thread[] workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(
                () -> consumeFrameQueue(traceSessionReader, pendingDirs, loadedFrames, producerDone),
                "frame-reader-worker-" + i
            );
            workers[i].start();
        }
        for (Thread worker : workers) {
            join(worker);
        }
        join(producer);

        List<LoadedFrame> ordered = new ArrayList<>(loadedFrames);
        ordered.sort(Comparator.comparing(LoadedFrame::dirName));

        List<FrameData> out = new ArrayList<>(ordered.size());
        for (LoadedFrame lf : ordered) {
            if (lf != null && lf.frameData() != null) {
                out.add(lf.frameData());
            }
        }
        return out;
    }

    private static void consumeFrameQueue(
        FrameJsonSessionReader traceSessionReader,
        ConcurrentLinkedQueue<Path> pendingDirs,
        ConcurrentLinkedQueue<LoadedFrame> loadedFrames,
        AtomicBoolean producerDone
    ) {
        while (true) {
            Path dir = pendingDirs.poll();
            if (dir == null) {
                if (producerDone.get()) {
                    return;
                }
                Thread.yield();
                continue;
            }
            FrameData frame = traceSessionReader.readFrameDirectory(dir);
            if (frame != null) {
                loadedFrames.add(new LoadedFrame(dir.getFileName().toString(), frame));
            }
        }
    }

    private static void produceFrameQueue(
        List<Path> frameDirs,
        ConcurrentLinkedQueue<Path> pendingDirs,
        AtomicBoolean producerDone
    ) {
        try {
            for (Path dir : frameDirs) {
                if (dir != null) {
                    pendingDirs.add(dir);
                }
            }
        }
        finally {
            producerDone.set(true);
        }
    }

    private static List<FrameData> filterFrames(
        List<FrameData> loaded,
        TileFiltererByConnectedComponents connectedComponentsFilterer,
        TileFiltererByGeometricNullNeighbors tileFilterer,
        FrameTextureNormalizerState model
    ) {
        if (loaded == null || loaded.isEmpty()) {
            return List.of();
        }
        List<FrameData> out = new ArrayList<>(loaded.size());
        for (FrameData frame : loaded) {
            if (frame == null) {
                continue;
            }
            List<TileInstance> ccFilteredTiles = connectedComponentsFilterer.filter(frame.getTiles());
            List<TileInstance> filteredTiles = tileFilterer.filter(ccFilteredTiles);
            filteredTiles = appendRelationshipAnchors(frame.getTiles(), filteredTiles);
            out.add(new FrameData(
                frame.getId(),
                filteredTiles,
                frame.getLines(),
                frame.getCameraState(),
                frame.getProjectionMatrix(),
                frame.getModelViewMatrix(),
                frame.isWithMatrixErrors()
            ));
        }
        return out;
    }

    private static List<TileInstance> appendRelationshipAnchors(
        List<TileInstance> sourceTiles,
        List<TileInstance> filteredTiles
    ) {
        if (sourceTiles == null || sourceTiles.isEmpty()) {
            return filteredTiles == null ? List.of() : filteredTiles;
        }
        Set<String> referencedIds = new LinkedHashSet<>();
        for (TileInstance tile : sourceTiles) {
            if (tile == null || tile.getUncles() == null) {
                continue;
            }
            tile.getUncles().stream()
                .filter(java.util.Objects::nonNull)
                .map(relationship -> ScopedTileIds.normalize(relationship.referenceContentId()))
                .filter(java.util.Objects::nonNull)
                .forEach(referencedIds::add);
        }
        if (referencedIds.isEmpty()) {
            return filteredTiles == null ? List.of() : filteredTiles;
        }

        List<TileInstance> out = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        if (filteredTiles != null) {
            for (TileInstance tile : filteredTiles) {
                if (tile != null && included.add(tile.getScopedId())) {
                    out.add(tile);
                }
            }
        }
        for (TileInstance tile : sourceTiles) {
            if (tile == null || tile.getRelationshipGeometries().isEmpty()) {
                continue;
            }
            String scopedId = ScopedTileIds.normalize(tile.getScopedId());
            if (referencedIds.contains(scopedId) && included.add(tile.getScopedId())) {
                out.add(tile);
            }
        }
        return List.copyOf(out);
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        boolean done = false;
        while (!done) {
            try {
                thread.join();
                done = true;
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                done = true;
            }
        }
    }

    private record LoadedFrame(String dirName, FrameData frameData) {
    }
}
