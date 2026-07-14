package pyramidalimagecoverage.options;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ArgumentParser {
    private ArgumentParser() {
    }

    public static CliArguments parse(String[] args) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            throw new IllegalArgumentException("Exactly one pyramidal image folder is required.");
        }
        Path folder = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Pyramidal image folder does not exist or is not a directory: " + folder);
        }
        if (!Files.isRegularFile(folder.resolve("0.png"))) {
            throw new IllegalArgumentException("Not a pyramidal image folder (missing 0.png): " + folder);
        }
        return new CliArguments(folder);
    }

    public static String usage() {
        return "Usage: gradle run --args=\"<pyramidalImageFolder>\"";
    }
}
