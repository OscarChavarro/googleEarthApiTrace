import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class X11AccessService {
    record X11Window(String id, String title, String windowClass) {
    }

    record WidgetInspection(int childWindowCount, String rawTree) {
    }

    private record CommandResult(int exitCode, String output) {
    }

    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern WINDOW_ID_PATTERN = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern QUOTED_VALUE_PATTERN = Pattern.compile("= \\\"(.*?)\\\"");
    private static final Pattern CHILD_WINDOW_PATTERN = Pattern.compile("(?m)^\\s+(0x[0-9a-fA-F]+)\\s+");
    private static final String GOOGLE_EARTH_X11_CLASS = "googleearth-bin";

    Optional<X11Window> findGoogleEarthWindow() {
        CommandResult clients = runCommand(QUERY_TIMEOUT, "xprop", "-root", "_NET_CLIENT_LIST");
        if (clients.exitCode() != 0) {
            throw new IllegalStateException("Could not list X11 client windows: " + clients.output());
        }

        Matcher idMatcher = WINDOW_ID_PATTERN.matcher(clients.output());
        while (idMatcher.find()) {
            String windowId = idMatcher.group();
            Optional<X11Window> window = readWindow(windowId);
            if (window.isPresent() && isGoogleEarth(window.get())) {
                return window;
            }
        }
        return Optional.empty();
    }

    Path captureWindow(X11Window window, Path outputPath) {
        try {
            Path absoluteOutput = outputPath.toAbsolutePath();
            Path parent = absoluteOutput.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(absoluteOutput);

            CommandResult capture = runCommand(
                CAPTURE_TIMEOUT,
                "import", "-window", window.id(), absoluteOutput.toString()
            );
            if (capture.exitCode() != 0 || !Files.isRegularFile(absoluteOutput)) {
                throw new IllegalStateException("Could not capture X11 window " + window.id()
                    + ": " + capture.output());
            }
            return absoluteOutput;
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare screenshot output: " + e.getMessage(), e);
        }
    }

    WidgetInspection inspectGoogleEarthWidgets(X11Window window) {
        CommandResult tree = runCommand(QUERY_TIMEOUT, "xwininfo", "-id", window.id(), "-tree");
        if (tree.exitCode() != 0) {
            throw new IllegalStateException("Could not inspect X11 child windows: " + tree.output());
        }

        int childWindowCount = 0;
        Matcher childMatcher = CHILD_WINDOW_PATTERN.matcher(tree.output());
        while (childMatcher.find()) {
            childWindowCount++;
        }
        return new WidgetInspection(childWindowCount, tree.output());
    }

    private Optional<X11Window> readWindow(String windowId) {
        CommandResult properties = runCommand(
            QUERY_TIMEOUT,
            "xprop", "-id", windowId, "_NET_WM_NAME", "WM_NAME", "WM_CLASS"
        );
        if (properties.exitCode() != 0) {
            return Optional.empty();
        }

        String title = "";
        String windowClass = "";
        for (String line : properties.output().split("\\R")) {
            if (line.startsWith("_NET_WM_NAME") || (title.isEmpty() && line.startsWith("WM_NAME"))) {
                title = extractQuotedValue(line).orElse(title);
            } else if (line.startsWith("WM_CLASS")) {
                windowClass = line.substring(line.indexOf('=') + 1).trim();
            }
        }
        return Optional.of(new X11Window(windowId, title, windowClass));
    }

    private boolean isGoogleEarth(X11Window window) {
        String normalizedClass = window.windowClass().toLowerCase(Locale.ROOT);
        return normalizedClass.contains(GOOGLE_EARTH_X11_CLASS);
    }

    private Optional<String> extractQuotedValue(String line) {
        Matcher matcher = QUOTED_VALUE_PATTERN.matcher(line);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private CommandResult runCommand(Duration timeout, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("X11 command timed out: " + String.join(" ", command));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return new CommandResult(process.exitValue(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("X11 command was interrupted: " + String.join(" ", command), e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not execute X11 command: " + String.join(" ", command), e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
