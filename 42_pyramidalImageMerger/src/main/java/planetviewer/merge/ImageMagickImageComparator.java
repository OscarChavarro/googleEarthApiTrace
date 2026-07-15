package planetviewer.merge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ImageMagickImageComparator {
    private static final double MAX_NORMALIZED_RMSE = 0.03;
    private static final long PROCESS_TIMEOUT_SECONDS = 30;
    private static final Pattern NORMALIZED_RMSE = Pattern.compile("\\(([-+0-9.eE]+)\\)");

    Comparison compare(Path destination, Path delta) throws IOException, InterruptedException {
        ImageSize destinationSize = identify(destination);
        ImageSize deltaSize = identify(delta);
        boolean deltaIsHigherResolution = resolutionScore(delta, deltaSize) > resolutionScore(destination, destinationSize);

        Path larger = deltaIsHigherResolution ? delta : destination;
        Path smaller = deltaIsHigherResolution ? destination : delta;
        ImageSize smallerSize = deltaIsHigherResolution ? destinationSize : deltaSize;
        List<String> command = List.of(
            "compare", "-metric", "RMSE",
            larger.toString(), "-resize", smallerSize.width() + "x" + smallerSize.height() + "!",
            smaller.toString(), "null:"
        );
        ProcessResult result = run(command);
        if (result.exitCode() > 1) {
            throw new IOException("ImageMagick compare failed: " + result.output().trim());
        }

        Matcher matcher = NORMALIZED_RMSE.matcher(result.output());
        if (!matcher.find()) {
            throw new IOException("Could not parse ImageMagick RMSE output: " + result.output().trim());
        }
        double normalizedRmse = Double.parseDouble(matcher.group(1));
        return new Comparison(normalizedRmse <= MAX_NORMALIZED_RMSE, deltaIsHigherResolution, normalizedRmse);
    }

    private ImageSize identify(Path image) throws IOException, InterruptedException {
        ProcessResult result = run(List.of("identify", "-format", "%w %h", image.toString()));
        if (result.exitCode() != 0) {
            throw new IOException("ImageMagick identify failed for " + image + ": " + result.output().trim());
        }
        String[] fields = result.output().trim().split("\\s+");
        if (fields.length != 2) {
            throw new IOException("Could not parse ImageMagick dimensions for " + image + ": " + result.output().trim());
        }
        return new ImageSize(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
    }

    private long resolutionScore(Path image, ImageSize size) throws IOException {
        long pixels = (long) size.width() * size.height();
        // Same-sized low-detail tiles occur in the source data; encoded size is a useful
        // tie-breaker after visual equivalence has independently been established.
        return pixels * 1_000_000_000L + Math.min(Files.size(image), 999_999_999L);
    }

    private ProcessResult run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out running: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    record Comparison(boolean visuallyEquivalent, boolean deltaIsHigherResolution, double normalizedRmse) {
    }

    private record ImageSize(int width, int height) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
