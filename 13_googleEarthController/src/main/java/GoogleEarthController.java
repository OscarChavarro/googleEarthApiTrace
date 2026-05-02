import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class GoogleEarthController {
    private static final long CONTINUE_DELAY_SECONDS = 10;
    private static final long KEY_HOLD_MILLIS = 400;
    private static final long BETWEEN_KEYS_MILLIS = 1000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object timerLock = new Object();
    private final AtomicLong inactivityToken = new AtomicLong(0);

    private final DetectorProcessClient detectorClient = new DetectorProcessClient();
    private GoogleEarthUi ui;

    private volatile boolean running;
    private volatile ScheduledFuture<?> inactivityTimer;

    public static void main(String[] args) {
        new GoogleEarthController().run();
    }

    private void run() {
        int totalPlacemarks = new KmlTurtlePlacemarkCounter().countFromUserHome();
        ui = new GoogleEarthUi(this::toggleStartStop, this::onQuit, totalPlacemarks);
        ui.initializeRobot();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
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
        boolean started = detectorClient.start(executable, "/tmp/output", this::onDetectorLine);
        if (!started) {
            running = false;
            return;
        }

        running = true;
        scheduleInactivityCycle();
    }

    private void stop() {
        if (!running) {
            detectorClient.stop();
            return;
        }

        running = false;
        cancelInactivityTimer();
        inactivityToken.incrementAndGet();
        detectorClient.stop();
    }

    private void onDetectorLine(String line) {
        if (!running) {
            return;
        }
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

    private void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onQuit() {
        detectorClient.sendExit();
        stop();
        scheduler.shutdownNow();
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
}
