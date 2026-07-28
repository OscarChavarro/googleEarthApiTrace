package demresampler.options;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record CliOptions(Path inputRoot, Path outputRoot, int threads, boolean resume) {
    public static CliOptions parse(String[] arguments) {
        List<String> positional = new ArrayList<>();
        int threads = Runtime.getRuntime().availableProcessors();
        boolean resume = false;

        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            if (argument.equals("--resume")) {
                resume = true;
            } else if (argument.equals("--threads")) {
                if (++i >= arguments.length) {
                    throw new IllegalArgumentException("--threads requires an integer");
                }
                threads = parseThreads(arguments[i]);
            } else if (argument.startsWith("--threads=")) {
                threads = parseThreads(argument.substring("--threads=".length()));
            } else if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            } else {
                positional.add(argument);
            }
        }

        if (positional.size() != 2) {
            throw new IllegalArgumentException(
                "Expected <inputFabdemFolder> and <outputPyramidFolder>");
        }
        return new CliOptions(
            Path.of(positional.get(0)).toAbsolutePath().normalize(),
            Path.of(positional.get(1)).toAbsolutePath().normalize(),
            threads,
            resume
        );
    }

    public static String usage() {
        return "Usage: ./run.sh <inputFabdemFolder> <outputPyramidFolder> "
            + "[--threads <n>] [--resume]";
    }

    private static int parseThreads(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 256) {
                throw new IllegalArgumentException("--threads must be in [1, 256]");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--threads must be an integer: " + value);
        }
    }
}
