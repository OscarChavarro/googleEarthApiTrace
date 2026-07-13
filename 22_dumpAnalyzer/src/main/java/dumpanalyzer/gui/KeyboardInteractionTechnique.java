package dumpanalyzer.gui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.List;

import dumpanalyzer.model.state.DumpAnalyzerState;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public final class KeyboardInteractionTechnique implements KeyListener {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter JSON_PRETTY_PRINTER = createPrettyPrinter();

    private final DumpAnalyzerState model;
    private final Runnable closeAction;
    private final CameraControllerOrbiter cameraController;
    private final Runnable repaintAction;

    public KeyboardInteractionTechnique(
        DumpAnalyzerState model,
        Runnable closeAction,
        CameraControllerOrbiter cameraController,
        Runnable repaintAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        vsdk.toolkit.gui.KeyEvent event = AwtSystem.awt2vsdkEvent(e);
        char keyChar = e.getKeyChar();
        if (keyChar == 't') {
            event.keycode = vsdk.toolkit.gui.KeyEvent.KEY_F8;
            if (model.processRendererConfigurationKey(event)) {
                repaintAction.run();
                return;
            }
        }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_ESCAPE -> closeAction.run();
            case KeyEvent.VK_1 -> {
                model.selectPreviousFrame();
            }
            case KeyEvent.VK_2 -> {
                model.selectNextFrame();
            }
            case KeyEvent.VK_3 -> model.selectPreviousTile();
            case KeyEvent.VK_4 -> model.selectNextTile();
            case KeyEvent.VK_C -> model.toggleActiveCamera();
            case KeyEvent.VK_F4 -> {
                if (model.processRendererConfigurationKey(event)) {
                    repaintAction.run();
                }
            }
            case KeyEvent.VK_T -> {
                if (keyChar == 'T') {
                    model.toggleShowGuiTextures();
                    repaintAction.run();
                }
            }
            default -> {
                if (model.processRendererConfigurationKey(event)) {
                    repaintAction.run();
                }
                else if (cameraController != null && cameraController.processKeyPressedEvent(event)) {
                    repaintAction.run();
                }
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (cameraController != null && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

    private void printSelectedFrameJson() {
        DumpAnalyzerState.HudState state = model.snapshotHudState();
        List<Frame> frames = model.snapshotFrames();

        int idx = state.selectedFrameIndex();
        if (idx < 0 || idx >= frames.size()) {
            System.out.println("[dumpAnalyzer] No frame selected.");
            return;
        }

        Frame selected = frames.get(idx);
        try {
            String json = JSON_MAPPER.writer(JSON_PRETTY_PRINTER).writeValueAsString(selected);
            String aabbSummary = buildGlobalAabbSummary(selected);
            System.out.println("[dumpAnalyzer] Selected frame JSON:\n" + json + "\n---------------\n" + aabbSummary);
        } catch (JsonProcessingException ex) {
            System.out.println("[dumpAnalyzer] Could not serialize selected frame: " + ex.getMessage());
        }
    }

    private static String buildGlobalAabbSummary(Frame frame) {
        Vector3Dd min = null;
        Vector3Dd max = null;
        for (TileInstance tile : frame.getTiles()) {
            if (tile.getMin() == null || tile.getMax() == null) {
                continue;
            }
            if (min == null) {
                min = tile.getMin();
                max = tile.getMax();
                continue;
            }
            min = new Vector3Dd(
                Math.min(min.x(), tile.getMin().x()),
                Math.min(min.y(), tile.getMin().y()),
                Math.min(min.z(), tile.getMin().z())
            );
            max = new Vector3Dd(
                Math.max(max.x(), tile.getMax().x()),
                Math.max(max.y(), tile.getMax().y()),
                Math.max(max.z(), tile.getMax().z())
            );
        }

        if (min == null || max == null) {
            return "Global AABB: null";
        }
        return "Global AABB:\n  min = " + min + "\n  max = " + max;
    }

    private static DefaultPrettyPrinter createPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", System.lineSeparator());
        printer = printer.withArrayIndenter(indenter);
        printer = printer.withObjectIndenter(indenter);
        return printer;
    }
}
