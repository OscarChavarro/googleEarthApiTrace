import java.nio.file.Path;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class GoogleEarthController {
    private static final String CRASH_DIALOG_TITLE = "Google Earth Crash Detected";
    private static final String OUTPUT_DIRECTORY = loadOutputDirectory();
    private static final long CONTINUE_DELAY_SECONDS = 2;
    private static final long KEY_HOLD_MILLIS = 430;
    private static final long BETWEEN_KEYS_MILLIS = 400;
    private static final long AFTER_TURTLE_DISABLE_MILLIS = 2000;
    private static final long BETWEEN_CHILD_ENABLE_ACTIONS_MILLIS = 1000;
    private static final long AFTER_CRASH_DIALOG_CLICK_MILLIS = 1500;
    private static final int WINDOW_RECOVERY_ATTEMPTS = 10;
    private static final long WINDOW_RECOVERY_RETRY_MILLIS = 500;
    private static final long MENU_DISCOVERY_TIMEOUT_MILLIS = 10_000;
    private static final long MENU_DISCOVERY_RETRY_MILLIS = 250;
    private static final long AFTER_FILE_MENU_ACTIVATION_MILLIS = 750;
    private static final long GOOGLE_EARTH_EXIT_TIMEOUT_MILLIS = 10_000;
    private static final long GOOGLE_EARTH_EXIT_POLL_MILLIS = 250;
    private static final long FALLBACK_TERMINATION_TIMEOUT_MILLIS = 3_000;
    private static final long OFFLINE_START_DELAY_SECONDS = 2;
    private static final char[] SPINNER_FRAMES = {'-', '/', '|', '\\'};

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object timerLock = new Object();
    private final Object spinnerLock = new Object();
    // Invalidates pending inactivity timers: bumped on every reschedule (e.g. detector activity).
    private final AtomicLong timerToken = new AtomicLong(0);
    // Invalidates in-flight interaction sequences: bumped only when the session stops.
    private final AtomicLong sessionToken = new AtomicLong(0);

    private final DetectorProcessClient detectorClient = new DetectorProcessClient();
    private final X11AccessService x11AccessService = new X11AccessService();
    private final QtAccessService qtAccessService = new QtAccessService();
    private final KmlTurtlePlacemarkCounter.CountResult placemarkCountResult =
        new KmlTurtlePlacemarkCounter().countFromUserHome();
    private GoogleEarthUi ui;

    private volatile boolean running;
    private volatile boolean starting;
    private ScheduledFuture<?> inactivityTimer;
    private int spinnerIndex;
    private int completedAdvanceCount;
    private int selectedPlacemarkLimit;
    private final boolean offline;

    private GoogleEarthController(boolean offline) {
        this.offline = offline;
    }

    public static void main(String[] args) {
        boolean offline = Arrays.asList(args).contains("--offline");
        new GoogleEarthController(offline).run();
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
        if (offline) {
            System.out.println("[STEP] Offline mode will activate START in 2 seconds.");
            scheduler.schedule(ui::clickStart, OFFLINE_START_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void toggleStartStop() {
        if (starting) {
            return;
        }

        if (running) {
            stop();
            return;
        }

        starting = true;
        selectedPlacemarkLimit = ui.getSelectedPlacemarkLimit();
        ui.setCompleted(false);
        ui.setStarting(true);
        scheduler.execute(this::start);
    }

    private void start() {
        if (running) {
            finishStarting();
            return;
        }

        ui.setFeedback(null);
        Optional<X11AccessService.X11Window> googleEarthWindow;
        try {
            googleEarthWindow = x11AccessService.findGoogleEarthWindow();
        } catch (RuntimeException e) {
            System.err.println("[ERROR] X11 access failed: " + e.getMessage());
            ui.setFeedback("X11 access failed");
            finishStarting();
            return;
        }

        if (googleEarthWindow.isEmpty()) {
            System.out.println("[WARN] Google Earth window not found.");
            ui.setFeedback("Google Earth window not found");
            finishStarting();
            return;
        }

        X11AccessService.X11Window window = googleEarthWindow.get();
        System.out.println("[OK] Google Earth X11 window found: " + window.id()
            + " title=\"" + window.title() + "\" class=" + window.windowClass());

        if (dismissCrashDialogIfPresent()) {
            Optional<X11AccessService.X11Window> recoveredWindow = recoverGoogleEarthWindowAfterCrashDialog();
            if (recoveredWindow.isEmpty()) {
                System.err.println("[ERROR] Google Earth crash dialog was dismissed but no main window became available.");
                ui.setFeedback("Google Earth main window not found");
                finishStarting();
                return;
            }
            window = recoveredWindow.get();
            System.out.println("[OK] Google Earth window recovered after crash dialog: " + window.id()
                + " title=\"" + window.title() + "\" class=" + window.windowClass());
        }

        try {
            X11AccessService.WidgetInspection inspection =
                x11AccessService.inspectGoogleEarthWidgets(window);
            System.out.println("[INFO] Google Earth X11 child windows found: "
                + inspection.childWindowCount() + ".");
            if (inspection.childWindowCount() == 0) {
                System.out.println("[INFO] No X11 widget hierarchy was exposed by Google Earth.");
            }
        } catch (RuntimeException e) {
            System.err.println("[WARN] Google Earth X11 widget inspection failed: " + e.getMessage());
        }

        QtAccessService.TurtlePreparation turtlePreparation;
        try {
            turtlePreparation = qtAccessService.locateTurtlePreparation();
            QtAccessService.LocatedPoint firstPoint = turtlePreparation.firstNavigationPoint();
            System.out.println("[OK] Located first turtle point through AT-SPI: "
                + firstPoint.name() + " at (" + firstPoint.centerX() + ", "
                + firstPoint.centerY() + ") (" + firstPoint.accessiblePath() + ").");
        } catch (RuntimeException e) {
            System.err.println("[ERROR] Google Earth AT-SPI access failed: " + e.getMessage());
            ui.setFeedback("Google Earth AT-SPI point not found");
            finishStarting();
            return;
        }

        try {
            prepareTurtleSelection(turtlePreparation);
        } catch (RuntimeException e) {
            System.err.println("[ERROR] Google Earth checkbox preparation failed: " + e.getMessage());
            ui.setFeedback("Google Earth checkbox preparation failed");
            finishStarting();
            return;
        }

        Path executable = Path.of("..", "12_fileSystemChangesDetector", "build", "fileSystemChangesDetector");
        boolean started = detectorClient.start(executable, OUTPUT_DIRECTORY, this::onDetectorLine);
        if (!started) {
            running = false;
            System.out.println("[ERROR] fileSystemChangesDetector could not be executed.");
            finishStarting();
            return;
        }

        running = true;
        finishStarting();
        System.out.println("[OK] fileSystemChangesDetector is running.");
        QtAccessService.LocatedPoint firstPoint = turtlePreparation.firstNavigationPoint();
        ui.clickAt(firstPoint.centerX(), firstPoint.centerY());
        System.out.println("[OK] Clicked first turtle point " + firstPoint.name() + ".");
        ui.pressAndHoldKey(java.awt.event.KeyEvent.VK_ENTER, KEY_HOLD_MILLIS);
        if (!running) {
            return;
        }
        System.out.println("[OK] Initial ENTER sent to " + firstPoint.name() + ".");
        markPointCompleted();
        if (shouldAutoStopAfterCompletedAdvance()) {
            ui.setCompleted(true);
            finishTraversalAndQuitGoogleEarth();
            return;
        }
        scheduleInactivityCycle();
        syncRunningStateToUi();
    }

    private void prepareTurtleSelection(QtAccessService.TurtlePreparation preparation) {
        clickPreparationTarget(preparation.turtleCheckbox());
        sleepInterruptibly(AFTER_TURTLE_DISABLE_MILLIS);

        for (int index = 0; index < preparation.initialItems().size(); index++) {
            QtAccessService.AccessibleItem item = preparation.initialItems().get(index);
            ui.pressAndHoldKey(java.awt.event.KeyEvent.VK_DOWN, KEY_HOLD_MILLIS);
            ui.pressAndHoldKey(java.awt.event.KeyEvent.VK_SPACE, KEY_HOLD_MILLIS);
            System.out.println("[OK] Enabled checkbox for " + item.name()
                + " with DOWN + SPACE.");
            if (index + 1 < preparation.initialItems().size()) {
                sleepInterruptibly(BETWEEN_CHILD_ENABLE_ACTIONS_MILLIS);
            }
        }
        System.out.println("[OK] Reset turtle visibility and selected its first two items.");
    }

    private void clickPreparationTarget(QtAccessService.LocatedPoint target) {
        ui.clickAt(target.centerX(), target.centerY());
        System.out.println("[OK] Clicked checkbox for " + target.name() + " at ("
            + target.centerX() + ", " + target.centerY() + ").");
    }

    private boolean dismissCrashDialogIfPresent() {
        Optional<QtAccessService.LocatedPoint> continueButton;
        try {
            continueButton = qtAccessService.locateCrashDialogContinueButton();
        } catch (RuntimeException e) {
            System.err.println("[WARN] Google Earth crash dialog inspection failed: " + e.getMessage());
            return false;
        }

        if (continueButton.isEmpty()) {
            return false;
        }

        QtAccessService.LocatedPoint target = continueButton.get();
        ui.clickAt(target.centerX(), target.centerY());
        System.out.println("[OK] Clicked crash dialog button " + target.name() + " at ("
            + target.centerX() + ", " + target.centerY() + ").");
        sleepInterruptibly(AFTER_CRASH_DIALOG_CLICK_MILLIS);
        return true;
    }

    private Optional<X11AccessService.X11Window> recoverGoogleEarthWindowAfterCrashDialog() {
        for (int attempt = 0; attempt < WINDOW_RECOVERY_ATTEMPTS; attempt++) {
            Optional<X11AccessService.X11Window> window = x11AccessService.findGoogleEarthWindow();
            if (window.isPresent() && !CRASH_DIALOG_TITLE.equals(window.get().title())) {
                return window;
            }
            sleepInterruptibly(WINDOW_RECOVERY_RETRY_MILLIS);
        }
        return Optional.empty();
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

        markPointCompleted();
        if (shouldAutoStopAfterCompletedAdvance()) {
            ui.setCompleted(true);
            finishTraversalAndQuitGoogleEarth();
        }
    }

    private void markPointCompleted() {
        ui.markAdvanceCompleted();
        completedAdvanceCount++;
    }

    private void logTraversalCompleted() {
        System.out.println("[OK] Finished traversing " + selectedPlacemarkLimit + " points.");
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
        requestGoogleEarthQuit();
        ui.shutdown();
        scheduler.shutdownNow();
        System.out.println("[OK] Controller shutdown complete.");
        System.exit(0);
    }

    private void finishTraversalAndQuitGoogleEarth() {
        stop();
        if (requestGoogleEarthQuit()) {
            logTraversalCompleted();
        }
        else {
            System.err.println("[ERROR] Google Earth did not close after File, UP, ENTER and PID fallback.");
        }
    }

    private boolean requestGoogleEarthQuit() {
        try {
            Optional<X11AccessService.X11Window> initialWindow =
                x11AccessService.findGoogleEarthWindow();
            if (initialWindow.isEmpty()) {
                System.out.println("[OK] Google Earth is already closed.");
                return true;
            }
        } catch (RuntimeException e) {
            System.err.println("[WARN] Could not inspect the initial Google Earth shutdown state: "
                + e.getMessage());
        }

        System.out.println("[STEP] Requesting Google Earth quit through AT-SPI File, then UP, ENTER.");
        if (!retryAtSpiAction(qtAccessService::activateFileMenu, "File menu")) {
            System.err.println("[WARN] AT-SPI could not activate the Google Earth File menu.");
        }
        else {
            System.out.println("[OK] Google Earth File menu activated through AT-SPI.");
            sleepInterruptibly(AFTER_FILE_MENU_ACTIVATION_MILLIS);
            ui.pressUpThenEnter(KEY_HOLD_MILLIS, BETWEEN_KEYS_MILLIS);
            System.out.println("[OK] Google Earth Exit requested with UP, ENTER.");
        }

        if (waitForGoogleEarthToClose(GOOGLE_EARTH_EXIT_TIMEOUT_MILLIS)) {
            System.out.println("[OK] Google Earth closed normally after File -> Exit.");
            return true;
        }

        Optional<X11AccessService.X11Window> remainingWindow;
        try {
            remainingWindow = x11AccessService.findGoogleEarthWindow();
        } catch (RuntimeException e) {
            System.err.println("[WARN] Could not resolve Google Earth's PID for fallback termination: "
                + e.getMessage());
            return false;
        }
        if (remainingWindow.isEmpty()) {
            return true;
        }
        X11AccessService.X11Window window = remainingWindow.get();
        if (window.processId() <= 0) {
            System.err.println("[WARN] Google Earth's X11 window did not publish a PID; "
                + "refusing to run a broad fallback kill.");
            return false;
        }
        System.err.println("[WARN] Google Earth is still open after 10 seconds; terminating only PID "
            + window.processId() + ".");
        if (!x11AccessService.terminateGoogleEarthProcess(window)) {
            return false;
        }
        return waitForGoogleEarthToClose(FALLBACK_TERMINATION_TIMEOUT_MILLIS);
    }

    private boolean retryAtSpiAction(java.util.function.BooleanSupplier action, String description) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MENU_DISCOVERY_TIMEOUT_MILLIS);
        RuntimeException lastFailure = null;
        do {
            try {
                if (action.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException e) {
                lastFailure = e;
            }
            sleepInterruptibly(MENU_DISCOVERY_RETRY_MILLIS);
        } while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted());
        if (lastFailure != null) {
            System.err.println("[WARN] AT-SPI " + description + " lookup failed: "
                + lastFailure.getMessage());
        }
        return false;
    }

    private boolean waitForGoogleEarthToClose(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            try {
                if (x11AccessService.findGoogleEarthWindow().isEmpty()) {
                    return true;
                }
            } catch (RuntimeException e) {
                System.err.println("[WARN] Could not inspect Google Earth shutdown state: "
                    + e.getMessage());
            }
            sleepInterruptibly(GOOGLE_EARTH_EXIT_POLL_MILLIS);
        } while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted());
        try {
            return x11AccessService.findGoogleEarthWindow().isEmpty();
        } catch (RuntimeException e) {
            System.err.println("[WARN] Final Google Earth shutdown inspection failed: "
                + e.getMessage());
            return false;
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

    private boolean shouldAutoStopAfterCompletedAdvance() {
        return completedAdvanceCount >= selectedPlacemarkLimit;
    }

    private void syncRunningStateToUi() {
        if (ui != null) {
            ui.setRunning(running);
        }
    }

    private void finishStarting() {
        starting = false;
        if (ui != null) {
            ui.setStarting(false);
            syncRunningStateToUi();
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
