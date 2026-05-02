import java.awt.AWTException;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

public class GoogleEarthController {
    private static final long CONTINUE_DELAY_SECONDS = 10;
    private static final long KEY_HOLD_MILLIS = 400;
    private static final long BETWEEN_KEYS_MILLIS = 1000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object processLock = new Object();
    private final Object timerLock = new Object();
    private final AtomicLong inactivityToken = new AtomicLong(0);

    private volatile Process detectorProcess;
    private volatile BufferedWriter detectorStdin;
    private volatile Thread detectorReaderThread;
    private volatile boolean running;
    private volatile ScheduledFuture<?> inactivityTimer;
    private volatile int lineCount;

    private JButton startStopButton;
    private JLabel counterLabel;
    private Robot robot;

    public static void main(String[] args) {
        new GoogleEarthController().run();
    }

    private void run() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopDetector();
            scheduler.shutdownNow();
        }));

        try {
            robot = new Robot();
        } catch (AWTException ex) {
            throw new IllegalStateException("Could not initialize keyboard robot", ex);
        }

        SwingUtilities.invokeLater(this::createAndShowWindow);
    }

    private void createAndShowWindow() {
        JFrame frame = new JFrame("GoogleEarthController");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setLayout(new FlowLayout());

        startStopButton = new JButton("START");
        startStopButton.setOpaque(true);
        startStopButton.setBackground(Color.GREEN);
        startStopButton.addActionListener(e -> toggleStartStop());

        JButton quitButton = new JButton("QUIT");
        quitButton.addActionListener(e -> onQuit());

        counterLabel = new JLabel("0");

        frame.add(startStopButton);
        frame.add(quitButton);
        frame.add(counterLabel);
        frame.setSize(260, 100);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void toggleStartStop() {
        if (!running) {
            startDetector();
        } else {
            stopDetector();
        }
        refreshStartStopButton();
    }

    private void refreshStartStopButton() {
        if (running) {
            startStopButton.setText("STOP");
            startStopButton.setBackground(Color.RED);
        } else {
            startStopButton.setText("START");
            startStopButton.setBackground(Color.GREEN);
        }
    }

    private void startDetector() {
        synchronized (processLock) {
            if (running) {
                return;
            }

            Path executable = Path.of("..", "12_fileSystemChangesDetector", "build", "fileSystemChangesDetector");
            try {
                Process process = new ProcessBuilder(executable.toString(), "/tmp/output")
                    .redirectErrorStream(true)
                    .start();

                detectorProcess = process;
                detectorStdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                running = true;
                scheduleInactivityCycle();

                detectorReaderThread = new Thread(() -> readDetectorOutput(process), "detector-output-reader");
                detectorReaderThread.setDaemon(true);
                detectorReaderThread.start();
            } catch (IOException ex) {
                System.err.println("Could not start detector process: " + ex.getMessage());
                running = false;
            }
        }
    }

    private void stopDetector() {
        Process processToStop;
        Thread readerThreadToJoin;

        synchronized (processLock) {
            if (!running && detectorProcess == null) {
                return;
            }

            running = false;
            cancelInactivityTimer();
            inactivityToken.incrementAndGet();

            processToStop = detectorProcess;
            readerThreadToJoin = detectorReaderThread;

            detectorProcess = null;
            detectorReaderThread = null;

            if (detectorStdin != null) {
                try {
                    detectorStdin.close();
                } catch (IOException ignored) {
                    // Best effort close.
                }
                detectorStdin = null;
            }

            if (processToStop != null) {
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

    private void readDetectorOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!running || process != detectorProcess) {
                    break;
                }
                onDetectorLine(line);
            }
        } catch (IOException ex) {
            if (running) {
                System.err.println("Error reading detector output: " + ex.getMessage());
            }
        }
    }

    private void onDetectorLine(String line) {
        lineCount++;
        SwingUtilities.invokeLater(() -> counterLabel.setText(Integer.toString(lineCount)));
        scheduleInactivityCycle();
    }

    private void scheduleInactivityCycle() {
        long token = inactivityToken.incrementAndGet();
        cancelInactivityTimer();

        ScheduledFuture<?> timer = scheduler.schedule(
            () -> onInactivityTimeout(token),
            CONTINUE_DELAY_SECONDS,
            TimeUnit.SECONDS
        );

        synchronized (timerLock) {
            inactivityTimer = timer;
        }
    }

    private void onInactivityTimeout(long token) {
        if (!isCurrentToken(token)) {
            return;
        }

        runInteractionSequence(token);

        if (isCurrentToken(token)) {
            scheduleInactivityCycle();
        }
    }

    private boolean isCurrentToken(long token) {
        return running && inactivityToken.get() == token;
    }

    private void runInteractionSequence(long token) {
        pressAndHoldKey(KeyEvent.VK_DOWN, token);
        if (!isCurrentToken(token)) {
            return;
        }

        sleepInterruptibly(BETWEEN_KEYS_MILLIS);
        if (!isCurrentToken(token)) {
            return;
        }

        pressAndHoldKey(KeyEvent.VK_ENTER, token);
    }

    private void pressAndHoldKey(int keyCode, long token) {
        robot.keyPress(keyCode);
        sleepInterruptibly(KEY_HOLD_MILLIS);
        if (isCurrentToken(token)) {
            robot.keyRelease(keyCode);
        }
    }

    private void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onQuit() {
        sendExitToDetector();
        stopDetector();
        scheduler.shutdownNow();
        System.exit(0);
    }

    private void sendExitToDetector() {
        synchronized (processLock) {
            if (detectorStdin == null) {
                return;
            }
            try {
                detectorStdin.write("exit\n");
                detectorStdin.flush();
            } catch (IOException ex) {
                System.err.println("Could not write exit to detector stdin: " + ex.getMessage());
            }
        }
    }

    private void cancelInactivityTimer() {
        synchronized (timerLock) {
            if (inactivityTimer != null) {
                inactivityTimer.cancel(false);
                inactivityTimer = null;
            }
        }
    }
}
