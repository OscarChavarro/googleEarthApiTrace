package frametexturenormalizer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OfflineOutputPathResolver {
    private OfflineOutputPathResolver() {
    }

    public static List<String> buildOfflineOutputPaths(String requestedOutput, int count) {
        String value = requestedOutput == null || requestedOutput.isBlank()
            ? "/tmp/frameTextureNormalizer_offline.png"
            : requestedOutput.trim();
        Path raw = Path.of(value);
        boolean treatAsDirectory = isDirectoryLike(raw, value);
        if (count <= 1) {
            Path out = treatAsDirectory ? raw.resolve("frame.png") : raw;
            if (!ensureParentDirectory(out)) {
                return List.of();
            }
            return List.of(out.toString());
        }
        Path dir;
        String base;
        String extension;
        if (treatAsDirectory) {
            dir = raw;
            base = "frame";
            extension = ".png";
        } else {
            Path parent = raw.getParent();
            dir = parent == null ? Path.of(".") : parent;
            String fileName = raw.getFileName() == null ? "frame.png" : raw.getFileName().toString();
            base = stripExtension(fileName);
            extension = fileExtension(fileName);
            if (base.isBlank()) {
                base = "frame";
            }
            if (extension.isBlank()) {
                extension = ".png";
            }
        }
        try {
            Files.createDirectories(dir);
        }
        catch (IOException ex) {
            System.out.println("Offline error: could not create output directory " + dir + ": " + ex.getMessage());
            return List.of();
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(dir.resolve(base + "_" + String.format("%04d", i + 1) + extension).toString());
        }
        return out;
    }

    public static boolean ensureParentDirectory(Path outputFile) {
        Path parent = outputFile.getParent();
        if (parent == null) {
            return true;
        }
        try {
            Files.createDirectories(parent);
            return true;
        }
        catch (IOException ex) {
            System.out.println("Offline error: could not create output directory " + parent + ": " + ex.getMessage());
            return false;
        }
    }

    public static boolean isDirectoryLike(Path path, String rawValue) {
        try {
            if (Files.exists(path) && Files.isDirectory(path)) {
                return true;
            }
        }
        catch (Exception ignored) {
        }
        if (rawValue.endsWith("/") || rawValue.endsWith("\\")) {
            return true;
        }
        Path fileName = path.getFileName();
        if (fileName == null) {
            return true;
        }
        return !fileName.toString().contains(".");
    }

    public static String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    public static String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot);
    }
}
