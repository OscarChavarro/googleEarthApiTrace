package dumpanalyzer.model.replay;

import java.util.List;

/** An ordered OpenGL draw retained for faithful replay, independently of tiles. */
public record ReplayDraw(
    int sequence,
    long glCall,
    String pass,
    String coordinateSpace,
    String primitive,
    List<ReplayVertex> vertices,
    double[] projectionMatrix,
    double[] modelViewMatrix,
    double[] textureMatrix,
    ReplayViewport viewport,
    ReplayScissor scissor,
    ReplayTexture texture,
    ReplayState state
) {
    public ReplayDraw {
        vertices = vertices == null ? List.of() : List.copyOf(vertices);
        projectionMatrix = copy(projectionMatrix);
        modelViewMatrix = copy(modelViewMatrix);
        textureMatrix = copy(textureMatrix);
    }
    private static double[] copy(double[] value) { return value == null ? null : value.clone(); }
    @Override public double[] projectionMatrix() { return copy(projectionMatrix); }
    @Override public double[] modelViewMatrix() { return copy(modelViewMatrix); }
    @Override public double[] textureMatrix() { return copy(textureMatrix); }
}
