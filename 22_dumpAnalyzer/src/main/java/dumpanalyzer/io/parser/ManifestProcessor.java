package dumpanalyzer.io.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class ManifestProcessor {
    private ManifestProcessor() {
    }

    static Path resolveIndicesBlobPath(Path frameDirectory, ManifestIndex manifest, long glCallNumber, int drawCount) {
        if (glCallNumber >= 0) {
            Path fromManifest = manifest.drawByCall.get(glCallNumber);
            if (fromManifest != null) return fromManifest;
            Path stableName = frameDirectory.resolve("drawElements_indices_call_" + glCallNumber + ".bin");
            if (Files.exists(stableName)) return stableName;
        }
        return frameDirectory.resolve("drawElements_indices_" + drawCount + ".bin");
    }

    static Path resolveVertexBlobPath(Path frameDirectory, ManifestIndex manifest, Long vertexAttribCall, int vertexAttribExportCallCount, int expectedAttribIndex) {
        if (vertexAttribCall != null) {
            long[] candidates = {vertexAttribCall, vertexAttribCall + 1L, vertexAttribCall - 1L};
            for (long candidateCall : candidates) {
                VertexManifestEntry fromManifest = manifest.vertexByCallAndIndex.get(vertexKey(candidateCall, expectedAttribIndex));
                if (fromManifest != null) return fromManifest.filePath();
                if (expectedAttribIndex >= 0) {
                    VertexManifestEntry fromAnyIndex = manifest.vertexByCallAndIndex.get(vertexKey(candidateCall, -1));
                    if (fromAnyIndex != null) return fromAnyIndex.filePath();
                }
                Path stableName = frameDirectory.resolve("glVertexAttribPointer_vertexAttrib_call_" + candidateCall + ".bin");
                if (Files.exists(stableName)) return stableName;
            }
        }
        if (vertexAttribExportCallCount < 0) return frameDirectory.resolve("__missing__.bin");
        return frameDirectory.resolve("glVertexAttribPointer_vertexAttrib_" + vertexAttribExportCallCount + ".bin");
    }

    static ManifestIndex loadManifestIndex(Path frameDirectory) {
        ManifestIndex out = new ManifestIndex();
        if (frameDirectory == null) return out;
        for (Path dir : candidateManifestDirectories(frameDirectory)) {
            Path manifestPath = dir.resolve("manifest.txt");
            if (!Files.exists(manifestPath)) continue;
            List<String> lines;
            try {
                lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                continue;
            }
            for (String line : lines) {
                Map<String, String> kv = parseManifestLine(line);
                String kind = kv.get("kind");
                String callText = kv.get("call");
                String fileText = kv.get("file");
                if (kind == null || callText == null || fileText == null) continue;
                long call;
                try {
                    call = Long.parseLong(callText);
                }
                catch (NumberFormatException ex) {
                    continue;
                }
                Path filePath = Paths.get(fileText);
                if (!filePath.isAbsolute()) filePath = dir.resolve(fileText);
                if ("draw_elements".equals(kind)) {
                    out.drawByCall.putIfAbsent(call, filePath);
                }
                else if ("vertex_attrib".equals(kind)) {
                    Integer attribIndex = tryParseInt(kv.get("attribIndex"));
                    if (attribIndex != null) {
                        out.vertexByCallAndIndex.putIfAbsent(vertexKey(call, attribIndex), new VertexManifestEntry(filePath, attribIndex));
                        out.vertexByCallAndIndex.putIfAbsent(vertexKey(call, -1), new VertexManifestEntry(filePath, attribIndex));
                    }
                }
            }
        }
        return out;
    }

    private static List<Path> candidateManifestDirectories(Path frameDirectory) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        out.add(frameDirectory);
        Path parent = frameDirectory.getParent();
        if (parent == null) return new ArrayList<>(out);
        String name = frameDirectory.getFileName().toString();
        Integer current = tryParseInt(name);
        if (current == null) return new ArrayList<>(out);
        out.add(parent.resolve(String.format("%05d", current + 1)));
        if (current > 0) out.add(parent.resolve(String.format("%05d", current - 1)));
        return new ArrayList<>(out);
    }

    private static Map<String, String> parseManifestLine(String line) {
        Map<String, String> kv = new HashMap<>();
        if (line == null || line.isBlank()) return kv;
        String[] parts = line.trim().split("\\s+");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq >= part.length() - 1) continue;
            kv.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return kv;
    }

    private static Integer tryParseInt(String text) {
        if (text == null) return null;
        try { return Integer.parseInt(text); } catch (NumberFormatException ex) { return null; }
    }

    private static String vertexKey(long call, int attribIndex) {
        return call + ":" + attribIndex;
    }

    static final class ManifestIndex {
        final Map<Long, Path> drawByCall = new HashMap<>();
        final Map<String, VertexManifestEntry> vertexByCallAndIndex = new HashMap<>();
    }

    private record VertexManifestEntry(Path filePath, int attribIndex) {
    }
}
