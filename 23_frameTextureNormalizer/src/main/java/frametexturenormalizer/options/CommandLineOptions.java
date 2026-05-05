package frametexturenormalizer.options;

public final class CommandLineOptions {
    private static final int DEFAULT_OFFLINE_WIDTH = 1280;
    private static final int DEFAULT_OFFLINE_HEIGHT = 720;
    private static final String DEFAULT_OFFLINE_OUTPUT = "/tmp/frameTextureNormalizer_offline.png";

    private CommandLineOptions() {
    }

    public static boolean hasArg(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    public static String getArgValue(String[] args, String prefix) {
        if (args == null) {
            return null;
        }
        for (String a : args) {
            if (a != null && a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }

    public static String getArgValue(String[] args, String flag, String prefix) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
            if (a.equals(flag) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return null;
    }

    public static int getIntValue(String[] args, String prefix, int fallback, int minValue) {
        String raw = getArgValue(args, prefix);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(minValue, Integer.parseInt(raw.trim()));
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static int getIntValue(String[] args, String flag, String prefix, int fallback, int minValue) {
        String raw = getArgValue(args, flag, prefix);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(minValue, Integer.parseInt(raw.trim()));
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static int offlineWidth(String[] args) {
        return getIntValue(args, "--width=", DEFAULT_OFFLINE_WIDTH, 1);
    }

    public static int offlineHeight(String[] args) {
        return getIntValue(args, "--height=", DEFAULT_OFFLINE_HEIGHT, 1);
    }

    public static int offlineFrameId(String[] args, int fallback) {
        return getIntValue(args, "--frame=", fallback, 0);
    }

    public static int startFrame(String[] args, int fallback) {
        return getIntValue(args, "--start-frame", "--start-frame=", fallback, 0);
    }

    public static int endFrame(String[] args, int fallback) {
        return getIntValue(args, "--end-frame", "--end-frame=", fallback, 0);
    }

    public static String offlineOutputPath(String[] args) {
        String value = getArgValue(args, "--output=");
        if (value == null || value.isBlank()) {
            return DEFAULT_OFFLINE_OUTPUT;
        }
        return value.trim();
    }
}
