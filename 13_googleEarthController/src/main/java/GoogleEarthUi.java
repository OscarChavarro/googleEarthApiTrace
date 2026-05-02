import java.awt.AWTException;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

final class GoogleEarthUi {
    private final Runnable onStartStop;
    private final Runnable onQuit;
    private final int totalPlacemarkCount;

    private JButton startStopButton;
    private JLabel progressLabel;
    private Robot robot;
    private int advanceCount;

    GoogleEarthUi(Runnable onStartStop, Runnable onQuit, int totalPlacemarkCount) {
        this.onStartStop = onStartStop;
        this.onQuit = onQuit;
        this.totalPlacemarkCount = totalPlacemarkCount;
        this.advanceCount = 0;
    }

    void initializeRobot() {
        try {
            robot = new Robot();
        } catch (AWTException ex) {
            throw new IllegalStateException("Could not initialize keyboard robot", ex);
        }
    }

    void showWindow() {
        SwingUtilities.invokeLater(this::createAndShowWindow);
    }

    void setRunning(boolean running) {
        SwingUtilities.invokeLater(() -> {
            if (running) {
                startStopButton.setText("STOP");
                startStopButton.setBackground(Color.RED);
            } else {
                startStopButton.setText("START");
                startStopButton.setBackground(Color.GREEN);
            }
        });
    }

    void markAdvanceCompleted() {
        advanceCount++;
        SwingUtilities.invokeLater(() -> progressLabel.setText(formatProgress()));
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

    private void createAndShowWindow() {
        JFrame frame = new JFrame("GoogleEarthController");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        frame.setLayout(new FlowLayout());

        startStopButton = new JButton("START");
        startStopButton.setOpaque(true);
        startStopButton.setBackground(Color.GREEN);
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
