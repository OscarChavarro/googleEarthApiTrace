package dumpanalyzer;

import java.nio.file.Path;

public final class FatalErrorHandler {
    private FatalErrorHandler() {
    }

    public static void fail(Path filePath, String message) {
        System.err.println("Parse failure in file: " + filePath.toAbsolutePath());
        System.err.println(message);
        System.exit(666);
    }
}
