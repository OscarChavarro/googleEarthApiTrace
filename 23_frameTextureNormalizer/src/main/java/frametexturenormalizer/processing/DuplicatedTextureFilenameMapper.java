package frametexturenormalizer.processing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import frametexturenormalizer.config.Configuration;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

public final class DuplicatedTextureFilenameMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<List<String>>> LIST_OF_LIST_OF_STRING =
        new TypeReference<>() {
        };
    private static final Path MAP_FILE = Path.of(Configuration.INPUT_PATH, "textureFileNamesMap.json");

    private DuplicatedTextureFilenameMapper() {
    }

    public static List<List<String>> loadOrCreate(List<FrameData> frames) {
        List<List<String>> loaded = loadFromDisk();
        if (loaded != null) {
            return loaded;
        }
        List<List<String>> computed = compute(frames);
        writeToDisk(computed);
        return computed;
    }

    private static List<List<String>> loadFromDisk() {
        if (!Files.exists(MAP_FILE)) {
            return null;
        }
        try {
            List<List<String>> parsed = JSON.readValue(MAP_FILE.toFile(), LIST_OF_LIST_OF_STRING);
            if (parsed == null) {
                return null;
            }
            return normalizeGroups(parsed);
        }
        catch (IOException ignored) {
            return null;
        }
    }

    private static List<List<String>> compute(List<FrameData> frames) {
        List<String> uniqueTextures = collectUniqueTexturePaths(frames);
        Map<String, List<String>> bySignature = groupBySignature(uniqueTextures);

        ConcurrentLinkedQueue<CompareTask> pendingComparisons = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<EqualPair> equalPairs = new ConcurrentLinkedQueue<>();
        AtomicBoolean producerDone = new AtomicBoolean(false);

        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        Thread[] workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(
                () -> consumeComparisonQueue(pendingComparisons, equalPairs, producerDone),
                "duplicated-texture-mapper-worker-" + i
            );
            workers[i].start();
        }

        produceComparisonTasks(bySignature, pendingComparisons);
        producerDone.set(true);

        for (Thread worker : workers) {
            join(worker);
        }

        return toGroups(uniqueTextures, equalPairs);
    }

    private static List<String> collectUniqueTexturePaths(List<FrameData> frames) {
        if (frames == null) {
            return List.of();
        }
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (FrameData frame : frames) {
            if (frame == null || frame.getTiles() == null) {
                continue;
            }
            for (TileInstance tile : frame.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String textureFile = tile.getTextureFile();
                if (textureFile == null || textureFile.isBlank()) {
                    continue;
                }
                if (unique.add(textureFile)) {
                    result.add(textureFile);
                }
            }
        }
        return result;
    }

    private static Map<String, List<String>> groupBySignature(List<String> texturePaths) {
        Map<String, List<String>> grouped = new HashMap<>();
        for (String texturePath : texturePaths) {
            String signature = readSignatureValue(texturePath);
            if (signature == null || signature.isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(signature, key -> new ArrayList<>()).add(texturePath);
        }
        return grouped;
    }

    private static String readSignatureValue(String texturePath) {
        if (texturePath == null || texturePath.isBlank()) {
            return null;
        }
        Path signaturePath = signaturePath(Path.of(texturePath));
        if (signaturePath == null || !Files.exists(signaturePath)) {
            return null;
        }
        try {
            String line = Files.readString(signaturePath).trim();
            if (line.isBlank()) {
                return null;
            }
            int firstSpace = line.indexOf(' ');
            return firstSpace < 0 ? line : line.substring(0, firstSpace).trim();
        }
        catch (IOException ignored) {
            return null;
        }
    }

    private static Path signaturePath(Path texturePath) {
        if (texturePath == null) {
            return null;
        }
        String name = texturePath.getFileName() == null ? "" : texturePath.getFileName().toString();
        if (name.isBlank()) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        String signatureName = dot > 0 ? name.substring(0, dot) + ".signature" : name + ".signature";
        Path parent = texturePath.getParent();
        return parent == null ? Path.of(signatureName) : parent.resolve(signatureName);
    }

    private static void produceComparisonTasks(
        Map<String, List<String>> bySignature,
        ConcurrentLinkedQueue<CompareTask> out
    ) {
        if (bySignature == null || out == null) {
            return;
        }
        for (List<String> candidates : bySignature.values()) {
            if (candidates == null || candidates.size() < 2) {
                continue;
            }
            for (int i = 0; i < candidates.size(); i++) {
                for (int j = i + 1; j < candidates.size(); j++) {
                    out.add(new CompareTask(candidates.get(i), candidates.get(j)));
                }
            }
        }
    }

    private static void consumeComparisonQueue(
        ConcurrentLinkedQueue<CompareTask> pendingComparisons,
        ConcurrentLinkedQueue<EqualPair> equalPairs,
        AtomicBoolean producerDone
    ) {
        while (true) {
            CompareTask task = pendingComparisons.poll();
            if (task == null) {
                if (producerDone.get()) {
                    return;
                }
                Thread.yield();
                continue;
            }
            if (filesEqual(task.a(), task.b())) {
                equalPairs.add(new EqualPair(task.a(), task.b()));
            }
        }
    }

    private static boolean filesEqual(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        try {
            return Files.mismatch(Path.of(first), Path.of(second)) == -1L;
        }
        catch (IOException ignored) {
            return false;
        }
    }

    private static List<List<String>> toGroups(List<String> allPaths, ConcurrentLinkedQueue<EqualPair> equalPairs) {
        UnionFind uf = new UnionFind(allPaths);
        for (EqualPair pair : equalPairs) {
            uf.union(pair.a(), pair.b());
        }

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String path : allPaths) {
            String root = uf.find(path);
            grouped.computeIfAbsent(root, key -> new ArrayList<>()).add(path);
        }

        List<List<String>> result = new ArrayList<>();
        for (List<String> group : grouped.values()) {
            if (group.size() > 1) {
                Collections.sort(group);
                result.add(group);
            }
        }
        return normalizeGroups(result);
    }

    private static List<List<String>> normalizeGroups(List<List<String>> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        Set<String> allNodes = new HashSet<>();
        for (List<String> group : groups) {
            if (group == null) {
                continue;
            }
            for (String path : group) {
                if (path != null && !path.isBlank()) {
                    allNodes.add(path);
                }
            }
        }
        UnionFind uf = new UnionFind(new ArrayList<>(allNodes));
        for (List<String> group : groups) {
            if (group == null || group.size() < 2) {
                continue;
            }
            String first = null;
            for (String path : group) {
                if (path == null || path.isBlank()) {
                    continue;
                }
                if (first == null) {
                    first = path;
                }
                else {
                    uf.union(first, path);
                }
            }
        }

        Map<String, List<String>> normalized = new HashMap<>();
        for (String path : allNodes) {
            String root = uf.find(path);
            normalized.computeIfAbsent(root, key -> new ArrayList<>()).add(path);
        }

        List<List<String>> result = new ArrayList<>();
        for (List<String> group : normalized.values()) {
            if (group.size() > 1) {
                Collections.sort(group);
                result.add(group);
            }
        }
        result.sort((a, b) -> a.get(0).compareTo(b.get(0)));
        return result;
    }

    private static void writeToDisk(List<List<String>> groups) {
        try {
            Files.createDirectories(MAP_FILE.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(MAP_FILE.toFile(), groups);
        }
        catch (IOException ignored) {
        }
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

    private record CompareTask(String a, String b) {
    }

    private record EqualPair(String a, String b) {
    }

    private static final class UnionFind {
        private final Map<String, String> parent;

        private UnionFind(List<String> nodes) {
            this.parent = new HashMap<>();
            for (String node : nodes) {
                this.parent.put(node, node);
            }
        }

        private String find(String node) {
            String p = parent.get(node);
            if (p == null) {
                return node;
            }
            if (p.equals(node)) {
                return p;
            }
            String root = find(p);
            parent.put(node, root);
            return root;
        }

        private void union(String a, String b) {
            String rootA = find(a);
            String rootB = find(b);
            if (rootA.equals(rootB)) {
                return;
            }
            if (rootA.compareTo(rootB) < 0) {
                parent.put(rootB, rootA);
            }
            else {
                parent.put(rootA, rootB);
            }
        }
    }
}
