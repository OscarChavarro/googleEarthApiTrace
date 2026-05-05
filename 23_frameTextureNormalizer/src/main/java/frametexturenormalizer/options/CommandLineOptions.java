package frametexturenormalizer.options;

public final class CommandLineOptions {
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
}
