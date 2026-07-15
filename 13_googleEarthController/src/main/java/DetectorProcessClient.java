import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

final class DetectorProcessClient {
    private static final long GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS = 1000;

    private final Object lock = new Object();

    private Process process;
    private BufferedWriter stdin;
    private Thread readerThread;

    boolean start(Path executable, String outputFolder, Consumer<String> onLine) {
        synchronized (lock) {
            if (process != null) {
                return true;
            }

            try {
                Process newProcess = new ProcessBuilder(executable.toString(), outputFolder)
                    .redirectErrorStream(true)
                    .start();

                process = newProcess;
                stdin = new BufferedWriter(new OutputStreamWriter(newProcess.getOutputStream(), StandardCharsets.UTF_8));
                readerThread = new Thread(() -> readOutput(newProcess, onLine), "detector-output-reader");
                readerThread.setDaemon(true);
                readerThread.start();
                return true;
            } catch (IOException ex) {
                System.err.println("[ERROR] Could not start detector process: " + ex.getMessage());
                process = null;
                stdin = null;
                readerThread = null;
                return false;
            }
        }
    }

    void sendExit() {
        synchronized (lock) {
            if (stdin == null) {
                return;
            }
            try {
                stdin.write("exit");
                stdin.newLine();
                stdin.flush();
                System.out.println("[OK] Exit command sent to detector process.");
            } catch (IOException ex) {
                System.err.println("[ERROR] Could not write exit to detector stdin: " + ex.getMessage());
            }
        }
    }

    void stop() {
        Process processToStop;
        Thread readerThreadToJoin;
        BufferedWriter stdinToClose;
        boolean gracefulShutdownRequested;

        synchronized (lock) {
            processToStop = process;
            readerThreadToJoin = readerThread;
            stdinToClose = stdin;
            gracefulShutdownRequested = stdinToClose != null;
            process = null;
            readerThread = null;
            stdin = null;
        }

        if (gracefulShutdownRequested) {
            try {
                stdinToClose.write("exit");
                stdinToClose.newLine();
                stdinToClose.flush();
                System.out.println("[OK] Exit command sent to detector process.");
            } catch (IOException ex) {
                System.err.println("[ERROR] Could not write exit to detector stdin: " + ex.getMessage());
            }
        }

        if (stdinToClose != null) {
            try {
                stdinToClose.close();
            } catch (IOException ignored) {
            }
        }

        if (processToStop != null) {
            try {
                if (!processToStop.waitFor(GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    processToStop.destroy();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                processToStop.destroy();
            }
        }

        if (readerThreadToJoin != null && readerThreadToJoin != Thread.currentThread()) {
            try {
                readerThreadToJoin.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void readOutput(Process trackedProcess, Consumer<String> onLine) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(trackedProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!isCurrentProcess(trackedProcess)) {
                    break;
                }
                onLine.accept(line);
            }
        } catch (IOException ex) {
            if (isCurrentProcess(trackedProcess)) {
                System.err.println("[ERROR] Error reading detector output: " + ex.getMessage());
            }
        }
    }

    private boolean isCurrentProcess(Process trackedProcess) {
        synchronized (lock) {
            return process == trackedProcess;
        }
    }
}
