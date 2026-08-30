package dumpanalyzer.ocr;

import dumpanalyzer.config.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LocalOcrEngine {
    private static volatile boolean loadAttempted = false;
    private static volatile boolean available = false;

    private LocalOcrEngine() {
    }

    public static String recognizePng(Path pngFile) {
        if (pngFile == null || !Files.isRegularFile(pngFile) || !ensureLoaded()) {
            return "";
        }
        try {
            String text = recognizePngNative(pngFile.toAbsolutePath().toString());
            return normalize(text);
        }
        catch (UnsatisfiedLinkError | RuntimeException ex) {
            return "";
        }
    }

    public static boolean isAvailable() {
        return ensureLoaded();
    }

    private static boolean ensureLoaded() {
        if (loadAttempted) {
            return available;
        }
        synchronized (LocalOcrEngine.class) {
            if (loadAttempted) {
                return available;
            }
            loadAttempted = true;
            Path library = Configuration.LOCAL_OCR_LIBRARY;
            if (library == null || !Files.isRegularFile(library)) {
                available = false;
                return false;
            }
            try {
                System.load(library.toAbsolutePath().toString());
                configureNative(Configuration.LOCAL_OCR_LANG);
                available = true;
            }
            catch (UnsatisfiedLinkError | SecurityException ex) {
                available = false;
            }
            return available;
        }
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .distinct()
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");
    }

    private static native void configureNative(String language);
    private static native String recognizePngNative(String filename);
}
