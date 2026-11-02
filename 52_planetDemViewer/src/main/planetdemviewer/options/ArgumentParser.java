package planetdemviewer.options;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import planetdemviewer.config.StorageProfile;

/**
 * Command-line argument parsing, following 32_pyramidalImageExporter's Main
 * conventions: the two positional args are the required DEM and imagery
 * pyramid folders, in that order; flags are --offline, --width, --height,
 * --output, --wires, --storage-profile and --mcp-port.
 */
public final class ArgumentParser {
    private static final int DEFAULT_OFFLINE_WIDTH = 1024;
    private static final int DEFAULT_OFFLINE_HEIGHT = 1024;
    private static final String DEFAULT_OFFLINE_OUTPUT = "/tmp/planetDemViewer_offline.png";
    private static final String[] VALUE_FLAGS = {"--width", "--height", "--output", "--storage-profile", "--mcp-port"};

    private ArgumentParser() {
    }

    /** Throws IllegalArgumentException, with a message for the user, unless both folders are given and exist. */
    public static CliArguments parse(String[] args) {
        boolean offline = hasArg(args, "--offline");
        boolean wires = hasArg(args, "--wires");
        int width = intArgValue(args, "--width", DEFAULT_OFFLINE_WIDTH);
        int height = intArgValue(args, "--height", DEFAULT_OFFLINE_HEIGHT);
        String output = stringArgValue(args, "--output", DEFAULT_OFFLINE_OUTPUT);
        StorageProfile storageProfile = StorageProfile.parse(stringArgValue(args, "--storage-profile", "slow"));
        int mcpPort = intArgValue(args, "--mcp-port", 0);
        List<String> folders = parsePositionalArgs(args);
        if (folders.size() != 2) {
            throw new IllegalArgumentException(
                "Expected the DEM folder and the imagery folder, but got " + folders.size() + " folder(s)");
        }
        String demFolder = requireDirectory("DEM", folders.get(0));
        String imageryFolder = requireDirectory("Imagery", folders.get(1));
        return new CliArguments(demFolder, imageryFolder, offline, wires, width, height, output, storageProfile,
            mcpPort);
    }

    private static String requireDirectory(String role, String folder) {
        if (!Files.isDirectory(Path.of(folder))) {
            throw new IllegalArgumentException(role + " folder does not exist: " + folder);
        }
        return folder;
    }

    public static void printUsage() {
        System.err.println(
            "Usage: gradle run --args=\"<demFolder> <imageryFolder> "
                + "[--offline] [--width <px>] [--height <px>] [--output <path>] [--wires] "
                + "[--storage-profile slow|fast] [--mcp-port <port>]\""
        );
        System.err.println(
            "  <demFolder>: raw DEM pyramid (root 0.bin, then one directory per quadrant "
                + "digit, e.g. 3/0/3/0303.bin). Required."
        );
        System.err.println("  <imageryFolder>: color imagery pyramid of PNG files. Required.");
        System.err.println("  --storage-profile: slow (default, serialized I/O) or fast.");
    }

    private static boolean hasArg(String[] args, String flag) {
        if (args == null || flag == null) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String stringArgValue(String[] args, String flag, String fallback) {
        if (args == null || flag == null) {
            return fallback;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.equals(flag) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (arg.startsWith(flag + "=")) {
                return arg.substring(flag.length() + 1);
            }
        }
        return fallback;
    }

    private static int intArgValue(String[] args, String flag, int fallback) {
        String raw = stringArgValue(args, flag, null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> parsePositionalArgs(String[] args) {
        List<String> positional = new ArrayList<>();
        if (args == null) {
            return positional;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if ("--offline".equals(arg) || "--wires".equals(arg)) {
                continue;
            }
            if (isValueFlag(arg)) {
                if (!arg.contains("=")) {
                    i++;
                }
                continue;
            }
            if (!arg.startsWith("--")) {
                positional.add(arg);
            }
        }
        return positional;
    }

    private static boolean isValueFlag(String arg) {
        for (String flag : VALUE_FLAGS) {
            if (arg.equals(flag) || arg.startsWith(flag + "=")) {
                return true;
            }
        }
        return false;
    }
}
