package planetviewer.options;

import java.util.ArrayList;
import java.util.List;

/**
 * Command-line argument parsing, following 32_pyramidalImageExporter's Main
 * conventions: positional args are pyramidal image root folders (zero or
 * more; an empty scene is valid, the user can load images from the GUI),
 * flags are --offline, --width, --height, --output, --wires.
 */
public final class ArgumentParser {
    private static final int DEFAULT_OFFLINE_WIDTH = 1024;
    private static final int DEFAULT_OFFLINE_HEIGHT = 1024;
    private static final String DEFAULT_OFFLINE_OUTPUT = "/tmp/planetViewer_offline.png";
    private static final String[] VALUE_FLAGS = {"--width", "--height", "--output"};

    private ArgumentParser() {
    }

    public static CliArguments parse(String[] args) {
        boolean offline = hasArg(args, "--offline");
        boolean wires = hasArg(args, "--wires");
        int width = intArgValue(args, "--width", DEFAULT_OFFLINE_WIDTH);
        int height = intArgValue(args, "--height", DEFAULT_OFFLINE_HEIGHT);
        String output = stringArgValue(args, "--output", DEFAULT_OFFLINE_OUTPUT);
        List<String> folders = parsePositionalArgs(args);
        return new CliArguments(folders, offline, wires, width, height, output);
    }

    public static void printUsage() {
        System.err.println(
            "Usage: gradle run --args=\"[<pyramidalImageFolder> ...] "
                + "[--offline] [--width <px>] [--height <px>] [--output <path>] [--wires]\""
        );
        System.err.println(
            "  <pyramidalImageFolder>: zero or more directories in the folder-based pyramidal "
                + "image format (root 0.png, then one directory per quadrant digit, e.g. "
                + "3/0/3/0303.png). With zero folders, the "
                + "viewer opens with an empty scene; use 'l' to load images from the GUI."
        );
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
