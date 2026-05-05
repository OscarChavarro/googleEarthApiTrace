package frametexturenormalizer.io;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.PyramidalImageModel;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.processing.TileFiltererByConnectedComponents;
import frametexturenormalizer.processing.TileFiltererByGeometricNullNeighbors;

public final class FrameReader {
    private FrameReader() {
    }

    public static Runnable loadTracedFrames(
        TraceSessionReader traceSessionReader,
        TileFiltererByConnectedComponents connectedComponentsFilterer,
        TileFiltererByGeometricNullNeighbors tileFilterer,
        PyramidalImageModel model
    ) {
        Runnable reloadTileMatrices = () -> {
            List<FrameData> loaded = readFramesParallel(traceSessionReader);
            List<FrameData> filtered = filterAndDeduplicate(loaded, connectedComponentsFilterer, tileFilterer, model);
            model.setFrames(filtered);
        };
        reloadTileMatrices.run();
        return reloadTileMatrices;
    }

    private static List<FrameData> readFramesParallel(TraceSessionReader traceSessionReader) {
        if (traceSessionReader == null) {
            return List.of();
        }

        List<Path> frameDirs = traceSessionReader.listFrameDirectories(Path.of(Configuration.INPUT_PATH));
        if (frameDirs.isEmpty()) {
            System.out.println("[frame-reader] no frame directories to process");
            return List.of();
        }
        System.out.println("[frame-reader] queued frame directories: " + frameDirs.size());

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
        System.out.println("[frame-reader] loaded frame.json files: " + ordered.size());

        List<FrameData> out = new ArrayList<>(ordered.size());
        for (LoadedFrame lf : ordered) {
            if (lf != null && lf.frameData() != null) {
                out.add(lf.frameData());
            }
        }
        return out;
    }

    private static void consumeFrameQueue(
        TraceSessionReader traceSessionReader,
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

    private static List<FrameData> filterAndDeduplicate(
        List<FrameData> loaded,
        TileFiltererByConnectedComponents connectedComponentsFilterer,
        TileFiltererByGeometricNullNeighbors tileFilterer,
        PyramidalImageModel model
    ) {
        if (loaded == null || loaded.isEmpty()) {
            return List.of();
        }
        List<FrameData> out = new ArrayList<>(loaded.size());
        Set<Integer> previousTileIds = null;
        int deduplicated = 0;
        for (FrameData frame : loaded) {
            if (frame == null) {
                continue;
            }
            Set<Integer> tileIds = tileIdSet(frame.getTiles());
            if (previousTileIds != null && previousTileIds.equals(tileIds)) {
                deduplicated++;
                continue;
            }
            List<TileInstance> ccFilteredTiles = connectedComponentsFilterer.filter(frame.getTiles());
            List<TileInstance> filteredTiles = tileFilterer.filter(ccFilteredTiles, model.getViewingCamera());
            if (frame.getId() == 100) {
                System.out.println(
                    "[frame-reader] frame 100: raw=" + (frame.getTiles() == null ? 0 : frame.getTiles().size())
                        + " cc=" + ccFilteredTiles.size()
                        + " geometric=" + filteredTiles.size()
                );
            }
            out.add(new FrameData(frame.getId(), filteredTiles, frame.getLines(), frame.getCameraState(), frame.isWithMatrixErrors()));
            previousTileIds = tileIds;
        }
        System.out.println(
            "[frame-reader] filter summary: loaded=" + loaded.size()
                + " deduplicated=" + deduplicated
                + " kept=" + out.size()
        );
        return out;
    }

    private static Set<Integer> tileIdSet(List<TileInstance> tiles) {
        Set<Integer> out = new LinkedHashSet<>();
        if (tiles == null) {
            return out;
        }
        for (TileInstance tile : tiles) {
            if (tile != null && tile.getTileId() >= 0) {
                out.add(tile.getTileId());
            }
        }
        return out;
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
