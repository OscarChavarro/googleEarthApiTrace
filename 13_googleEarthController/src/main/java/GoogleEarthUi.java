import java.awt.AWTException;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

final class GoogleEarthUi {
    private enum UiEventType {
        SHOW_WINDOW,
        CLICK_START,
        SET_STARTING,
        SET_RUNNING,
        ADVANCE_COMPLETED,
        SET_COMPLETED,
        SET_FEEDBACK
    }

    private static final class UiEvent {
        private final UiEventType type;
        private final boolean running;
        private final boolean starting;
        private final boolean completed;
        private final String feedback;

        private UiEvent(UiEventType type, boolean running, boolean starting, boolean completed, String feedback) {
            this.type = type;
            this.running = running;
            this.starting = starting;
            this.completed = completed;
            this.feedback = feedback;
        }

        static UiEvent showWindow() {
            return new UiEvent(UiEventType.SHOW_WINDOW, false, false, false, null);
        }

        static UiEvent clickStart() {
            return new UiEvent(UiEventType.CLICK_START, false, false, false, null);
        }

        static UiEvent setStarting(boolean starting) {
            return new UiEvent(UiEventType.SET_STARTING, false, starting, false, null);
        }

        static UiEvent setRunning(boolean running) {
            return new UiEvent(UiEventType.SET_RUNNING, running, false, false, null);
        }

        static UiEvent advanceCompleted() {
            return new UiEvent(UiEventType.ADVANCE_COMPLETED, false, false, false, null);
        }

        static UiEvent setCompleted(boolean completed) {
            return new UiEvent(UiEventType.SET_COMPLETED, false, false, completed, null);
        }

        static UiEvent setFeedback(String feedback) {
            return new UiEvent(UiEventType.SET_FEEDBACK, false, false, false, feedback);
        }
    }

    private final Runnable onStartStop;
    private final Runnable onQuit;
    private final int totalPlacemarkCount;
    private final int maximumSelectablePlacemarkCount;
    private final BlockingQueue<UiEvent> eventQueue = new LinkedBlockingQueue<>();

    private JButton startStopButton;
    private JLabel progressLabel;
    private JLabel statusLabel;
    private JTextField limitTextField;
    private Robot robot;
    private Thread uiEventConsumerThread;
    private volatile boolean uiConsumerRunning;
    private boolean runningState;
    private boolean startingState;
    private boolean completedState;
    private String feedbackState;
    private int advanceCount;

    GoogleEarthUi(Runnable onStartStop, Runnable onQuit, int totalPlacemarkCount) {
        this.onStartStop = onStartStop;
        this.onQuit = onQuit;
        this.totalPlacemarkCount = totalPlacemarkCount;
        this.maximumSelectablePlacemarkCount = Math.max(1, totalPlacemarkCount);
        this.advanceCount = 0;
        this.runningState = false;
        this.completedState = false;
        this.feedbackState = null;
    }

    int getSelectedPlacemarkLimit() {
        if (limitTextField == null) {
            return maximumSelectablePlacemarkCount;
        }

        int parsedValue = parseLimitValue(limitTextField.getText());
        String normalizedValue = Integer.toString(parsedValue);
        if (!normalizedValue.equals(limitTextField.getText().trim())) {
            limitTextField.setText(normalizedValue);
        }
        return parsedValue;
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

    void setStarting(boolean starting) {
        enqueueEvent(UiEvent.setStarting(starting));
    }

    void clickStart() {
        enqueueEvent(UiEvent.clickStart());
    }

    void markAdvanceCompleted() {
        enqueueEvent(UiEvent.advanceCompleted());
    }

    void setCompleted(boolean completed) {
        enqueueEvent(UiEvent.setCompleted(completed));
    }

    void setFeedback(String feedback) {
        enqueueEvent(UiEvent.setFeedback(feedback));
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

    void clickAt(int x, int y) {
        robot.mouseMove(x, y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    void pressDownThenEnter(long keyHoldMillis, long betweenKeysMillis) {
        pressAndHoldKey(KeyEvent.VK_DOWN, keyHoldMillis);
        sleepInterruptibly(betweenKeysMillis);
        pressAndHoldKey(KeyEvent.VK_ENTER, keyHoldMillis);
    }

    void quitGoogleEarthFromMenu(long keyHoldMillis, long betweenKeysMillis) {
        robot.keyPress(KeyEvent.VK_ALT);
        sleepInterruptibly(keyHoldMillis);
        robot.keyPress(KeyEvent.VK_F);
        sleepInterruptibly(keyHoldMillis);
        robot.keyRelease(KeyEvent.VK_F);
        robot.keyRelease(KeyEvent.VK_ALT);
        sleepInterruptibly(betweenKeysMillis);
        pressAndHoldKey(KeyEvent.VK_UP, keyHoldMillis);
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
            if (startStopButton == null || progressLabel == null || statusLabel == null) {
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

        if (event.type == UiEventType.SET_STARTING) {
            startingState = event.starting;
            refreshViewState();
            return;
        }

        if (event.type == UiEventType.CLICK_START) {
            if (startStopButton != null && !runningState && !startingState) {
                startStopButton.doClick();
            }
            return;
        }

        if (event.type == UiEventType.ADVANCE_COMPLETED) {
            advanceCount++;
            refreshViewState();
            return;
        }

        if (event.type == UiEventType.SET_COMPLETED) {
            completedState = event.completed;
            refreshViewState();
            return;
        }

        if (event.type == UiEventType.SET_FEEDBACK) {
            feedbackState = event.feedback;
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
        startStopButton.addActionListener(e -> {
            if (!startingState) {
                onStartStop.run();
            }
        });

        JButton quitButton = new JButton("QUIT");
        quitButton.addActionListener(e -> onQuit.run());

        progressLabel = new JLabel(formatProgress());
        statusLabel = new JLabel(formatStatus());
        limitTextField = new JTextField(Integer.toString(maximumSelectablePlacemarkCount), 6);
        limitTextField.addActionListener(e -> limitTextField.setText(Integer.toString(getSelectedPlacemarkLimit())));

        JPanel limitPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        limitPanel.add(new JLabel("Limit to: "));
        limitPanel.add(limitTextField);

        frame.add(startStopButton);
        frame.add(quitButton);
        frame.add(limitPanel);
        frame.add(progressLabel);
        frame.add(statusLabel);
        frame.setSize(260, 135);
        frame.setResizable(false);
        frame.setLocation(0, 0);
        frame.setVisible(true);
    }

    private void refreshViewState() {
        if (startStopButton != null) {
            if (startingState) {
                startStopButton.setText("STARTING");
                startStopButton.setBackground(Color.YELLOW);
            } else if (runningState) {
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

        if (statusLabel != null) {
            statusLabel.setText(formatStatus());
        }

        if (limitTextField != null) {
            limitTextField.setEnabled(!runningState && !startingState);
        }
    }

    private void enqueueEvent(UiEvent event) {
        eventQueue.offer(event);
    }

    private String formatProgress() {
        return advanceCount + "/" + totalPlacemarkCount;
    }

    private String formatStatus() {
        if (feedbackState != null && !feedbackState.isBlank()) {
            return feedbackState;
        }
        if (startingState) {
            return "Starting";
        }
        if (runningState) {
            return "Running";
        }
        if (completedState) {
            return "Completed";
        }
        return "Idle";
    }

    private int parseLimitValue(String rawValue) {
        try {
            int parsedValue = Integer.parseInt(rawValue.trim());
            if (parsedValue < 1) {
                return 1;
            }
            return Math.min(parsedValue, maximumSelectablePlacemarkCount);
        } catch (NumberFormatException ex) {
            return maximumSelectablePlacemarkCount;
        }
    }

    private void sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
