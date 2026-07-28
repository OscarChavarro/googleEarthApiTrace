package demresampler.gdal;

import demresampler.io.FabdemSourceTile;
import vsdk.toolkit.gui.feedback.ProgressMonitor;
import vsdk.toolkit.gui.feedback.ProgressMonitorConsoleLongFormat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TiffHeaderScanner {
    static final Duration DEFAULT_HEADER_TIMEOUT = Duration.ofSeconds(2);
    private static final String ERROR_PREFIX = "ERROR ";

    private TiffHeaderScanner() {
    }

    public static TiffHeaderScanResult scan(List<FabdemSourceTile> sources)
        throws IOException, InterruptedException {
        return scan(sources, DEFAULT_HEADER_TIMEOUT, ProcessProbe::new);
    }

    static TiffHeaderScanResult scan(
        List<FabdemSourceTile> sources,
        Duration timeout,
        ProbeFactory probeFactory
    ) throws IOException, InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("TIFF header timeout must be positive");
        }

        System.out.printf(
            "Checking %,d TIFF headers (timeout %.1f s per file):%n",
            sources.size(),
            timeout.toMillis() / 1000.0);
        ProgressMonitor progress = new ProgressMonitorConsoleLongFormat();
        progress.begin();
        List<FabdemSourceTile> readable = new ArrayList<>(sources.size());
        List<PendingTiff> pending = new ArrayList<>();
        TiffHeaderMetadata representativeHeader = null;
        try (HeaderProbe probe = probeFactory.create()) {
            for (int index = 0; index < sources.size(); index++) {
                FabdemSourceTile source = sources.get(index);
                try {
                    ProbeResult result = probe.probe(source.path(), timeout);
                    if (result.readable()) {
                        readable.add(source);
                        if (representativeHeader == null) {
                            representativeHeader = result.metadata();
                        }
                    } else {
                        pending.add(new PendingTiff(source.path(), result.reason()));
                    }
                } catch (IOException exception) {
                    pending.add(new PendingTiff(
                        source.path(),
                        "TIFF header probe I/O error: " + exception.getMessage()));
                } finally {
                    progress.update(0, sources.size(), index + 1);
                }
            }
        } finally {
            progress.end();
        }
        return new TiffHeaderScanResult(readable, pending, representativeHeader);
    }

    @FunctionalInterface
    interface ProbeFactory {
        HeaderProbe create() throws IOException;
    }

    interface HeaderProbe extends AutoCloseable {
        ProbeResult probe(Path path, Duration timeout)
            throws IOException, InterruptedException;

        @Override
        void close();
    }

    record ProbeResult(
        boolean readable,
        String reason,
        TiffHeaderMetadata metadata
    ) {
        static ProbeResult readableHeader(TiffHeaderMetadata metadata) {
            return new ProbeResult(true, "", metadata);
        }

        static ProbeResult pending(String reason) {
            return new ProbeResult(false, reason, null);
        }
    }

    private static final class ProcessProbe implements HeaderProbe {
        private final ExecutorService responseReader = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "tiff-header-probe-response");
            thread.setDaemon(true);
            return thread;
        });
        private Process process;
        private BufferedWriter input;
        private BufferedReader output;

        @Override
        public ProbeResult probe(Path path, Duration timeout)
            throws IOException, InterruptedException {
            ensureRunning();
            input.write(encode(path.toAbsolutePath().toString()));
            input.newLine();
            input.flush();

            Future<String> response = responseReader.submit(output::readLine);
            try {
                String line = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (line == null) {
                    stopProcess();
                    return ProbeResult.pending("TIFF header probe exited without a response");
                }
                if (line.startsWith("OK ")) {
                    try {
                        TiffHeaderMetadata metadata = TiffHeaderMetadata.parse(
                            decode(line.substring(3)));
                        return ProbeResult.readableHeader(metadata);
                    } catch (IllegalArgumentException exception) {
                        stopProcess();
                        return ProbeResult.pending(
                            "Invalid TIFF metadata response: " + exception.getMessage());
                    }
                }
                if (line.startsWith(ERROR_PREFIX)) {
                    return ProbeResult.pending(decode(line.substring(ERROR_PREFIX.length())));
                }
                stopProcess();
                return ProbeResult.pending("Invalid TIFF header probe response: " + line);
            } catch (TimeoutException exception) {
                stopProcess();
                return ProbeResult.pending(
                    "header read timed out after " + timeout.toMillis() + " ms");
            } catch (ExecutionException exception) {
                stopProcess();
                Throwable cause = exception.getCause();
                String message = cause == null ? exception.getMessage() : cause.getMessage();
                return ProbeResult.pending("TIFF header probe failed: " + message);
            }
        }

        private void ensureRunning() throws IOException {
            if (process != null && process.isAlive()) {
                return;
            }
            stopProcess();
            String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(
                javaExecutable,
                "-Djava.library.path=" + System.getProperty("java.library.path"),
                "-cp", System.getProperty("java.class.path"),
                TiffHeaderProbeMain.class.getName());
            builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            process = builder.start();
            input = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        }

        private void stopProcess() {
            boolean exited = true;
            if (process != null) {
                process.destroyForcibly();
                try {
                    exited = process.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    exited = false;
                }
                process = null;
            }
            // A process in uninterruptible kernel I/O can remain alive briefly even
            // after SIGKILL. Do not let stream close() make the parent wait for it.
            if (exited) {
                closeQuietly(input);
                closeQuietly(output);
            }
            input = null;
            output = null;
        }

        @Override
        public void close() {
            stopProcess();
            responseReader.shutdownNow();
        }

        private static void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception ignored) {
                // The child is being discarded; no recovery depends on its streams.
            }
        }

        private static String encode(String value) {
            return Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
        }

        private static String decode(String value) throws IOException {
            try {
                return new String(
                    Base64.getDecoder().decode(value),
                    StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw new EOFException("Malformed response from TIFF header probe");
            }
        }
    }
}
