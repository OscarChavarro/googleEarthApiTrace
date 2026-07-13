package frametexturenormalizer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.TileMatrix;

public final class FrameMatrixJsonExporter {
    public void export(List<TileMatrix> matrices) {
        Map<Integer, List<TileMatrix>> matricesByFrame = groupByFrame(matrices);
        cleanupObsoleteMatrices(matricesByFrame.keySet());
        if (matricesByFrame.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, List<TileMatrix>> entry : matricesByFrame.entrySet()) {
            MatrixJsonWriter.writeMatricesJson(entry.getKey(), entry.getValue());
        }
    }

    private static Map<Integer, List<TileMatrix>> groupByFrame(List<TileMatrix> matrices) {
        if (matrices == null || matrices.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<TileMatrix>> out = new LinkedHashMap<>();
        for (TileMatrix matrix : matrices) {
            if (matrix == null || matrix.getTiles() == null || matrix.getTiles().size() < 2) {
                continue;
            }
            out.computeIfAbsent(matrix.getFrameId(), unused -> new ArrayList<>()).add(matrix);
        }
        return out;
    }

    private static void cleanupObsoleteMatrices(Set<Integer> keptFrameIds) {
        Path outputRoot = Path.of(Configuration.INPUT_PATH);
        if (!Files.isDirectory(outputRoot)) {
            return;
        }
        Set<String> keptFrameDirs = new LinkedHashSet<>();
        if (keptFrameIds != null) {
            for (Integer frameId : keptFrameIds) {
                if (frameId != null) {
                    keptFrameDirs.add(String.format("%05d", frameId));
                }
            }
        }
        try (var dirs = Files.list(outputRoot)) {
            dirs.filter(Files::isDirectory)
                .forEach(dir -> cleanupFrameDir(dir, keptFrameDirs));
        }
        catch (IOException ignored) {
        }
    }

    private static void cleanupFrameDir(Path frameDir, Set<String> keptFrameDirs) {
        if (frameDir == null || frameDir.getFileName() == null) {
            return;
        }
        String dirName = frameDir.getFileName().toString();
        if (!dirName.matches("\\d+") || keptFrameDirs.contains(dirName)) {
            return;
        }
        try {
            Files.deleteIfExists(frameDir.resolve("matrix.json"));
            Files.deleteIfExists(frameDir.resolve("matrix.txt"));
        }
        catch (IOException ignored) {
        }
    }
}
