import java.awt.AWTException;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

final class GoogleEarthUi {
    private enum UiEventType {
        SHOW_WINDOW,
        SET_RUNNING,
        ADVANCE_COMPLETED
    }

    private static final class UiEvent {
        private final UiEventType type;
        private final boolean running;

        private UiEvent(UiEventType type, boolean running) {
            this.type = type;
            this.running = running;
        }

        static UiEvent showWindow() {
            return new UiEvent(UiEventType.SHOW_WINDOW, false);
        }

        static UiEvent setRunning(boolean running) {
            return new UiEvent(UiEventType.SET_RUNNING, running);
        }

        static UiEvent advanceCompleted() {
            return new UiEvent(UiEventType.ADVANCE_COMPLETED, false);
        }
    }

    private final Runnable onStartStop;
    private final Runnable onQuit;
    private final int totalPlacemarkCount;
    private final BlockingQueue<UiEvent> eventQueue = new LinkedBlockingQueue<>();

    private JButton startStopButton;
    private JLabel progressLabel;
    private Robot robot;
    private Thread uiEventConsumerThread;
    private volatile boolean uiConsumerRunning;
    private boolean runningState;
    private int advanceCount;

    GoogleEarthUi(Runnable onStartStop, Runnable onQuit, int totalPlacemarkCount) {
        this.onStartStop = onStartStop;
        this.onQuit = onQuit;
        this.totalPlacemarkCount = totalPlacemarkCount;
        this.advanceCount = 0;
        this.runningState = false;
    }

    void initializeRobot() {
        try {
            robot = new Robot();
        } catch (AWTException ex) {
            throw new IllegalStateException("Could not initialize keyboard robot", ex);
        }
    }

    void showWindow() {
        startUiEventConsumerIfNeeded();
        enqueueEvent(UiEvent.showWindow());
    }

    void setRunning(boolean running) {
        enqueueEvent(UiEvent.setRunning(running));
    }

    void markAdvanceCompleted() {
        enqueueEvent(UiEvent.advanceCompleted());
    }

    void shutdown() {
        uiConsumerRunning = false;
        if (uiEventConsumerThread != null) {
            uiEventConsumerThread.interrupt();
        }
    }

    void pressAndHoldKey(int keyCode, long holdMillis) {
        robot.keyPress(keyCode);
        sleepInterruptibly(holdMillis);
        robot.keyRelease(keyCode);
    }

    void pressDownThenEnter(long keyHoldMillis, long betweenKeysMillis) {
        pressAndHoldKey(KeyEvent.VK_DOWN, keyHoldMillis);
        sleepInterruptibly(betweenKeysMillis);
        pressAndHoldKey(KeyEvent.VK_ENTER, keyHoldMillis);
    }

    private void startUiEventConsumerIfNeeded() {
        if (uiEventConsumerThread != null) {
            return;
        }

        synchronized (this) {
            if (uiEventConsumerThread != null) {
                return;
            }

            uiConsumerRunning = true;
            uiEventConsumerThread = new Thread(this::consumeUiEvents, "ui-event-consumer");
            // Keep JVM alive while UI is active; daemon thread lets process exit immediately.
            uiEventConsumerThread.setDaemon(false);
            uiEventConsumerThread.start();
        }
    }

    private void consumeUiEvents() {
        while (uiConsumerRunning) {
            try {
                UiEvent event = eventQueue.take();
                SwingUtilities.invokeLater(() -> handleUiEvent(event));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void handleUiEvent(UiEvent event) {
        if (event.type == UiEventType.SHOW_WINDOW) {
            if (startStopButton == null || progressLabel == null) {
                createAndShowWindow();
            }
            refreshViewState();
            return;
        }

        if (event.type == UiEventType.SET_RUNNING) {
            runningState = event.running;
            refreshViewState();
            return;
        }

        if (event.type == UiEventType.ADVANCE_COMPLETED) {
            advanceCount++;
            refreshViewState();
        }
    }

    private void createAndShowWindow() {
        JFrame frame = new JFrame("GoogleEarthController");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setLayout(new FlowLayout());

        startStopButton = new JButton("START");
        startStopButton.setOpaque(true);
        startStopButton.addActionListener(e -> onStartStop.run());

        JButton quitButton = new JButton("QUIT");
        quitButton.addActionListener(e -> onQuit.run());

        progressLabel = new JLabel(formatProgress());

        frame.add(startStopButton);
        frame.add(quitButton);
        frame.add(progressLabel);
        frame.setSize(260, 100);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void refreshViewState() {
        if (startStopButton != null) {
            if (runningState) {
                startStopButton.setText("STOP");
                startStopButton.setBackground(Color.RED);
            } else {
                startStopButton.setText("START");
                startStopButton.setBackground(Color.GREEN);
            }
        }

        if (progressLabel != null) {
            progressLabel.setText(formatProgress());
        }
    }

    private void enqueueEvent(UiEvent event) {
        eventQueue.offer(event);
    }

    private String formatProgress() {
        return advanceCount + "/" + totalPlacemarkCount;
    }

    private void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
