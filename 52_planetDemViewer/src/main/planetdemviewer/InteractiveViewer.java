package planetdemviewer;

import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.Animator;
import java.applet.Applet;
import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.gui.KeyboardInteractionTechniques;
import planetdemviewer.gui.LoadPyramidalImageDialog;
import planetdemviewer.gui.MouseInteractionTechnique;
import planetdemviewer.gui.ViewActions;
import planetdemviewer.gui.awt.AwtMainWindow;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.dialog.LayerTreePresenter;
import planetdemviewer.render.jogl4.Jogl4PlanetViewerRenderer;
import planetdemviewer.state.ApplicationStateStore;
import planetdemviewer.terrain.TerrainSubdivisionScheme;
import vsdk.toolkit.animation.AnimationEvent;
import vsdk.toolkit.animation.AnimationEventGenerator;
import vsdk.toolkit.animation.AnimationListener;

@SuppressWarnings("removal")
public class InteractiveViewer extends Applet {
    private final PlanetViewerModel model;
    private final Jogl4PlanetViewerRenderer renderer;

    private boolean closing;
    private KeyboardInteractionTechniques keyboardInteraction;
    private MouseInteractionTechnique mouseInteraction;
    private GLCanvas canvas;
    private AwtMainWindow frame;
    private GuiController gui;
    private AutoCloseable mcpServer;
    private Thread animatorThread;
    private Animator joglAnimator;

    public InteractiveViewer(PlanetViewerModel model, Jogl4PlanetViewerRenderer renderer) {
        this.model = model;
        this.renderer = renderer;
    }

    public void launchDesktop() {
        fillGuiWithCanvas();

        frame = new AwtMainWindow(canvas, gui, new LayerTreePresenter(model), this::requestClose);
        frame.setVisible(true);
        startJoglAnimator();
        canvas.requestFocusInWindow();
    }

    @Override
    public void init() {
        fillGuiWithCanvas();
        setLayout(new BorderLayout());
        add("Center", canvas);
    }

    private void fillGuiWithCanvas() {
        gui = new GuiController(
            Configuration.GUI_DIRECTORY, Configuration.DEFAULT_GUI_LANGUAGE, this::requestClose, model);
        canvas = renderer.createCanvas();
        // JOGL owns its GL context on its animator thread. Tile I/O only updates caches.
        renderer.setRepaintOnTileReady(() -> { });
        mouseInteraction = new MouseInteractionTechnique(
            model,
            renderer.getCameraController(),
            renderer::selectViewAtPixel,
            renderer::selectTerrainAtPixel,
            () -> { },
            canvas::requestFocusInWindow
        );
        canvas.addMouseListener(mouseInteraction);
        canvas.addMouseMotionListener(mouseInteraction);
        canvas.addMouseWheelListener(mouseInteraction);
        ViewActions viewActions = new ViewActions(
            renderer::cycleSelectedView,
            renderer::cycleLayoutStyle,
            renderer::toggleCameraFrustumsVisible,
            renderer::addView,
            renderer::removeView,
            renderer::getSelectedViewRenderingConfiguration
        );
        keyboardInteraction = new KeyboardInteractionTechniques(
            model,
            this::requestClose,
            renderer.getCameraController(),
            () -> { },
            () -> LoadPyramidalImageDialog.showAndLoad(canvas, model, canvas::repaint),
            viewActions,
            renderer::cyclePalette,
            renderer::toggleTerrainTileBoundsVisible,
            renderer::toggleUniformLodVisible,
            this::saveState,
            this::clearState,
            renderer::toggleImageryVisible,
            renderer::deleteSelectedImageryTile,
            this::requestFullScreenToggle
        );
        canvas.addKeyListener(keyboardInteraction);
        canvas.setFocusable(true);
        startAnimator();
    }

    /** Deferred, because key handling holds the model lock that the OpenGL frame needs while the window resizes. */
    private void requestFullScreenToggle() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.toggleFullScreen();
            }
        });
    }

    /** Keeps a stable animation/render cadence even while there is no user input or tile I/O. */
    private void startAnimator() {
        if (animatorThread != null) {
            return;
        }
        AnimationEventGenerator animator = new AnimationEventGenerator();
        animator.addAnimationListener(new AnimationListener() {
            @Override
            public void tick(AnimationEvent event) {
                // Reserved for simulation/game updates. The JOGL animator renders independently.
            }
        });
        animatorThread = new Thread(animator, "planet-dem-animator");
        animatorThread.setDaemon(true);
        animatorThread.start();
    }

    private void startJoglAnimator() {
        if (joglAnimator != null) {
            return;
        }
        joglAnimator = new Animator(canvas);
        joglAnimator.setRunAsFastAsPossible(false);
        joglAnimator.start();
    }

    /** Accepts any java.awt.event.KeyEvent key code through the local MCP endpoint. */
    public void pressKeyFromMcp(int keyCode) throws Exception {
        runOnEventThread(() -> keyboardInteraction.keyPressed(new KeyEvent(
            canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED)));
    }

    public void setGeometryModeFromMcp(boolean enabled) throws Exception {
        runOnEventThread(() -> {
            renderer.setGeometryMode(enabled);
            canvas.repaint();
        });
    }

    public void setTileBoundsFromMcp(boolean enabled) throws Exception {
        runOnEventThread(() -> {
            renderer.setTerrainTileBoundsVisible(enabled);
            canvas.repaint();
        });
    }

    /** Follows the layer panel's rule: returns false, changing nothing, outside the 3D terrain mode. */
    public boolean setSubdivisionSchemeFromMcp(TerrainSubdivisionScheme scheme) throws Exception {
        boolean[] applied = new boolean[1];
        runOnEventThread(() -> {
            model.writeLocked(() -> {
                if (model.getOperationMode() == OperationModes.BASIC_TRIANGULATION) {
                    model.setTerrainSubdivisionScheme(scheme);
                    applied[0] = true;
                }
            });
            canvas.repaint();
        });
        return applied[0];
    }

    /** Sends a top-left-origin physical capture pixel to terrain selection through the local MCP endpoint. */
    public void clickViewportFromMcp(int xPixel, int yPixelFromTop) throws Exception {
        runOnEventThread(() -> model.writeLocked(() -> renderer.selectTerrainAtSurfacePixel(
            xPixel, yPixelFromTop)));
    }

    public String cameraModelJsonFromMcp() throws Exception {
        final String[] response = new String[1];
        runOnEventThread(() -> response[0] = renderer.selectedCameraModelJson());
        return response[0];
    }

    public Path captureSnapshotFromMcp() throws Exception {
        Path output = Files.createTempFile("planetdemviewer-mcp-", ".png");
        canvas.invoke(true, drawable -> {
            renderer.captureSnapshot(drawable, drawable.getGL().getGL4(), output);
            return true;
        });
        return output.toAbsolutePath();
    }

    public void setMcpServer(AutoCloseable server) {
        mcpServer = server;
    }

    private void saveState() {
        try {
            ApplicationStateStore.save(ApplicationStateStore.DEFAULT_PATH, model, renderer);
            model.setHudStatus("Saved state.json");
        }
        catch (Exception e) {
            model.setHudStatus("Could not save state.json: " + e.getMessage());
        }
        canvas.repaint();
    }

    private void clearState() {
        try {
            boolean removed = ApplicationStateStore.delete(ApplicationStateStore.DEFAULT_PATH);
            model.setHudStatus(removed ? "Deleted state.json" : "state.json did not exist");
        }
        catch (Exception e) {
            model.setHudStatus("Could not delete state.json: " + e.getMessage());
        }
        canvas.repaint();
    }

    private static void runOnEventThread(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        }
        else {
            SwingUtilities.invokeAndWait(action);
        }
    }

    private void requestClose() {
        if (closing) {
            return;
        }
        closing = true;
        Runnable closeTask = () -> {
            try {
                if (canvas != null) {
                    if (joglAnimator != null) {
                        joglAnimator.stop();
                    }
                    canvas.setVisible(false);
                    canvas.removeMouseListener(mouseInteraction);
                    canvas.removeMouseMotionListener(mouseInteraction);
                    canvas.removeMouseWheelListener(mouseInteraction);
                    canvas.removeKeyListener(keyboardInteraction);
                    canvas.destroy();
                }
            }
            catch (Throwable t) {
                System.out.println("InteractiveViewer: error while destroying canvas: " + t.getMessage());
            }
            finally {
                if (mcpServer != null) {
                    try {
                        mcpServer.close();
                    }
                    catch (Exception ignored) {
                        // Closing the window must not be blocked by the diagnostic endpoint.
                    }
                }
                if (frame != null) {
                    frame.dispose();
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            closeTask.run();
        }
        else {
            SwingUtilities.invokeLater(closeTask);
        }
    }
}
