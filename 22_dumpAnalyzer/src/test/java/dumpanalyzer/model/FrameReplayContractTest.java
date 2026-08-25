package dumpanalyzer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dumpanalyzer.model.replay.ReplayDraw;
import dumpanalyzer.model.replay.ReplayScissor;
import dumpanalyzer.model.replay.ReplayState;
import dumpanalyzer.model.replay.ReplayTexture;
import dumpanalyzer.model.replay.ReplayVertex;
import dumpanalyzer.model.replay.ReplayViewport;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameReplayContractTest {
    @Test
    void preservesReplayDrawsOutsideTheTileContract() {
        ReplayDraw draw = new ReplayDraw(7, 99, "SCREEN_SPACE", "PIXEL", "GL_TRIANGLE_STRIP",
            List.of(new ReplayVertex(1, 2, 0, 0, 0)), new double[16], new double[16], new double[16],
            new ReplayViewport(0, 0, 2179, 1558), new ReplayScissor(false, 0, 0, 0, 0),
            new ReplayTexture(0, "GL_TEXTURE_2D", 201, 0, 0, "/tmp/hud.png", 128, 32),
            new ReplayState(new double[] {1, 1, 1, 1}, true, "GL_SRC_ALPHA", "GL_ONE_MINUS_SRC_ALPHA", false, false, "GL_LESS", false, false, false, 6));
        Frame frame = new Frame(11452, List.of(), List.of(), null, null, null,
            new ReplayViewport(0, 0, 2179, 1558), List.of(draw));

        assertEquals(7, frame.getContractVersion());
        assertEquals(2179, frame.getCaptureSurface().width());
        assertEquals(List.of(draw), frame.getReplayDraws());
        assertEquals(List.of(), frame.getTiles());
    }
}
