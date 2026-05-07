package frametexturenormalizer.processing.preparation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

public final class Sha256SignatureGenerator {
    private Sha256SignatureGenerator() {
    }

    public static void verifyTextureFilesHasSignatureFile(List<FrameData> frames) {
        ConcurrentLinkedQueue<String> pendingPaths = new ConcurrentLinkedQueue<>();
        enqueueUniqueTexturePaths(frames, pendingPaths);

        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        Thread[] workers = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            workers[i] = new Thread(() -> consumeQueueAndValidate(pendingPaths), "sha-signature-worker-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) {
            join(worker);
        }
    }

    private static void enqueueUniqueTexturePaths(List<FrameData> frames, ConcurrentLinkedQueue<String> out) {
        if (frames == null || out == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (FrameData frame : frames) {
            if (frame == null || frame.getTiles() == null) {
                continue;
            }
            for (TileInstance tile : frame.getTiles()) {
                if (tile == null) {
                    continue;
                }
                String path = tile.getTextureFile();
                if (path == null || path.isBlank()) {
                    continue;
                }
                if (seen.add(path)) {
                    out.add(path);
                }
            }
        }
    }

    private static void consumeQueueAndValidate(ConcurrentLinkedQueue<String> pendingPaths) {
        while (true) {
            String texturePath = pendingPaths.poll();
            if (texturePath == null) {
                return;
            }
            ensureSignatureFile(texturePath);
        }
    }

    private static void ensureSignatureFile(String texturePath) {
        Path texture = Path.of(texturePath);
        Path signature = signaturePath(texture);
        if (signature == null || Files.exists(signature)) {
            return;
        }
        String line = runSha512sum(texturePath);
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            Files.writeString(signature, line + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        catch (IOException ignored) {
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

    private static String runSha512sum(String texturePath) {
        ProcessBuilder pb = new ProcessBuilder("sha512sum", texturePath);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
            ) {
                String line = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    return null;
                }
                return line;
            }
        }
        catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
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
}
