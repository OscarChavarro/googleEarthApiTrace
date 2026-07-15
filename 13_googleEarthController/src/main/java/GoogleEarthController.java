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
    private static final long CONTINUE_DELAY_SECONDS = 2;
    private static final long KEY_HOLD_MILLIS = 430;
    private static final long BETWEEN_KEYS_MILLIS = 1000;
    private static final char[] SPINNER_FRAMES = {'-', '/', '|', '\\'};

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object timerLock = new Object();
    private final Object spinnerLock = new Object();
    // Invalidates pending inactivity timers: bumped on every reschedule (e.g. detector activity).
    private final AtomicLong timerToken = new AtomicLong(0);
    // Invalidates in-flight interaction sequences: bumped only when the session stops.
    private final AtomicLong sessionToken = new AtomicLong(0);

    private final DetectorProcessClient detectorClient = new DetectorProcessClient();
    private final KmlTurtlePlacemarkCounter.CountResult placemarkCountResult =
        new KmlTurtlePlacemarkCounter().countFromUserHome();
    private GoogleEarthUi ui;

    private volatile boolean running;
    private ScheduledFuture<?> inactivityTimer;
    private int spinnerIndex;
    private int completedAdvanceCount;
    private int selectedPlacemarkLimit;

    public static void main(String[] args) {
        new GoogleEarthController().run();
    }

    private void run() {
        ui = new GoogleEarthUi(this::toggleStartStop, this::onQuit, placemarkCountResult.getCount());
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
    }

    private void start() {
        if (running) {
            return;
        }

        selectedPlacemarkLimit = ui.getSelectedPlacemarkLimit();
        ui.setCompleted(false);
        Path executable = Path.of("..", "12_fileSystemChangesDetector", "build", "fileSystemChangesDetector");
        boolean started = detectorClient.start(executable, OUTPUT_DIRECTORY, this::onDetectorLine);
        if (!started) {
            running = false;
            System.out.println("[ERROR] fileSystemChangesDetector could not be executed.");
            syncRunningStateToUi();
            return;
        }

        running = true;
        System.out.println("[OK] fileSystemChangesDetector is running.");
        if (shouldAutoStopAfterCompletedAdvance()) {
            System.out.println("[OK] Route already complete according to the current placemark limit.");
            ui.setCompleted(true);
            stop();
            return;
        }
        scheduleInactivityCycle();
        syncRunningStateToUi();
    }

    private void stop() {
        if (!running) {
            System.out.println("[STEP] Stop requested while controller is idle.");
            detectorClient.stop();
            syncRunningStateToUi();
            return;
        }

        running = false;
        sessionToken.incrementAndGet();
        cancelInactivityTimer();
        detectorClient.stop();
        System.out.println("[OK] Navigation session stopped.");
        syncRunningStateToUi();
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
        long session = sessionToken.get();

        synchronized (timerLock) {
            long token = timerToken.incrementAndGet();
            if (inactivityTimer != null) {
                inactivityTimer.cancel(false);
            }
            inactivityTimer = scheduler.schedule(
                () -> onInactivityTimeout(token, session),
                CONTINUE_DELAY_SECONDS,
                TimeUnit.SECONDS
            );
        }
    }

    private void onInactivityTimeout(long token, long session) {
        if (!isCurrentSession(session) || timerToken.get() != token) {
            return;
        }

        System.out.println("!");
        runInteractionSequence(session);

        if (isCurrentSession(session)) {
            scheduleInactivityCycle();
        }
    }

    private boolean isCurrentSession(long session) {
        return running && sessionToken.get() == session;
    }

    private void runInteractionSequence(long session) {
        runBlockingSync();
        if (!isCurrentSession(session)) {
            return;
        }

        ui.pressAndHoldKey(java.awt.event.KeyEvent.VK_DOWN, KEY_HOLD_MILLIS);
        if (!isCurrentSession(session)) {
            return;
        }

        sleepInterruptibly(BETWEEN_KEYS_MILLIS);
        if (!isCurrentSession(session)) {
            return;
        }

        ui.pressAndHoldKey(java.awt.event.KeyEvent.VK_ENTER, KEY_HOLD_MILLIS);
        if (!isCurrentSession(session)) {
            return;
        }

        ui.markAdvanceCompleted();
        completedAdvanceCount++;
        if (shouldAutoStopAfterCompletedAdvance()) {
            System.out.println("[OK] Reached the configured placemark limit.");
            ui.setCompleted(true);
            stop();
        }
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

    private boolean shouldAutoStopAfterCompletedAdvance() {
        return completedAdvanceCount >= selectedPlacemarkLimit;
    }

    private void syncRunningStateToUi() {
        if (ui != null) {
            ui.setRunning(running);
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
        return properties.getProperty("output.directory", "/media/ramdisk/output");
    }
}
