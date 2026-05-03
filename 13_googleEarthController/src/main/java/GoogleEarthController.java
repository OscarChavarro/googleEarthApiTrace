import java.nio.file.Path;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class GoogleEarthController {
    private static final String OUTPUT_DIRECTORY = loadOutputDirectory();
    private static final long CONTINUE_DELAY_SECONDS = 5;
    private static final long KEY_HOLD_MILLIS = 430;
    private static final long BETWEEN_KEYS_MILLIS = 1000;
    private static final char[] SPINNER_FRAMES = {'-', '/', '|', '\\'};

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object timerLock = new Object();
    private final Object spinnerLock = new Object();
    private final AtomicLong inactivityToken = new AtomicLong(0);

    private final DetectorProcessClient detectorClient = new DetectorProcessClient();
    private GoogleEarthUi ui;

    private volatile boolean running;
    private volatile ScheduledFuture<?> inactivityTimer;
    private int spinnerIndex;

    public static void main(String[] args) {
        new GoogleEarthController().run();
    }

    private void run() {
        int totalPlacemarks = new KmlTurtlePlacemarkCounter().countFromUserHome();
        ui = new GoogleEarthUi(this::toggleStartStop, this::onQuit, totalPlacemarks);
        ui.initializeRobot();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
            if (ui != null) {
                ui.shutdown();
            }
            scheduler.shutdownNow();
        }));

        ui.showWindow();
    }

    private void toggleStartStop() {
        if (!running) {
            start();
        } else {
            stop();
        }
        ui.setRunning(running);
    }

    private void start() {
        if (running) {
            return;
        }

        Path executable = Path.of("..", "12_fileSystemChangesDetector", "build", "fileSystemChangesDetector");
        boolean started = detectorClient.start(executable, OUTPUT_DIRECTORY, this::onDetectorLine);
        if (!started) {
            running = false;
            System.out.println("[ERROR] fileSystemChangesDetector could not be executed.");
            return;
        }

        running = true;
        System.out.println("[OK] fileSystemChangesDetector is running.");
        scheduleInactivityCycle();
    }

    private void stop() {
        if (!running) {
            System.out.println("[STEP] Stop requested while controller is idle.");
            detectorClient.stop();
            return;
        }

        running = false;
        cancelInactivityTimer();
        inactivityToken.incrementAndGet();
        detectorClient.stop();
        System.out.println("[OK] Navigation session stopped.");
    }

    private void onDetectorLine(String line) {
        if (!running) {
            return;
        }
        printDetectorSpinner();
        scheduleInactivityCycle();
    }

    private void printDetectorSpinner() {
        synchronized (spinnerLock) {
            char frame = SPINNER_FRAMES[spinnerIndex];
            spinnerIndex = (spinnerIndex + 1) % SPINNER_FRAMES.length;
            System.out.print("\r" + frame + "  ");
            System.out.flush();
        }
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

        System.out.println("!");
        runInteractionSequence(token);

        if (isCurrentToken(token)) {
            scheduleInactivityCycle();
        }
    }

    private boolean isCurrentToken(long token) {
        return running && inactivityToken.get() == token;
    }

    private void runInteractionSequence(long token) {
        runBlockingSync();
        if (!isCurrentToken(token)) {
            return;
        }

        ui.pressAndHoldKey(java.awt.event.KeyEvent.VK_DOWN, KEY_HOLD_MILLIS);
        if (!isCurrentToken(token)) {
            return;
        }

        sleepInterruptibly(BETWEEN_KEYS_MILLIS);
        if (!isCurrentToken(token)) {
            return;
        }

        ui.pressAndHoldKey(java.awt.event.KeyEvent.VK_ENTER, KEY_HOLD_MILLIS);
        if (!isCurrentToken(token)) {
            return;
        }

        ui.markAdvanceCompleted();
    }

    private void runBlockingSync() {
        try {
            Process process = new ProcessBuilder("sync").start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("[WARN] sync exited with code " + exitCode + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[WARN] sync was interrupted.");
        } catch (Exception e) {
            System.err.println("[WARN] Could not run sync: " + e.getMessage());
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
        System.out.println("[STEP] Quit requested.");
        detectorClient.sendExit();
        stop();
        ui.shutdown();
        scheduler.shutdownNow();
        System.out.println("[OK] Controller shutdown complete.");
        System.exit(0);
    }

    private void cancelInactivityTimer() {
        synchronized (timerLock) {
            if (inactivityTimer != null) {
                inactivityTimer.cancel(false);
                inactivityTimer = null;
            }
        }
    }

    private static String loadOutputDirectory() {
        Properties properties = new Properties();
        try (InputStream input = GoogleEarthController.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        }
        catch (Exception e) {
            System.err.println("[WARN] Could not load application.properties: " + e.getMessage());
        }
        return properties.getProperty("output.directory", "/tmp/output");
    }
}
