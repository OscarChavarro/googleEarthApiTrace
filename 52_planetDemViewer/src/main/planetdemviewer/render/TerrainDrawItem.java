package planetdemviewer.render;

import java.util.concurrent.CompletableFuture;
import planetdemviewer.processing.DrawCommand;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/**
 * One terrain tile to draw. The preparation is null when no mesh is being generated:
 * either the mesh is already resident on the GPU, or its DEM page is not ready yet.
 * The bounds color is {red, green, blue}, or null when the bounds stay hidden.
 */
public record TerrainDrawItem(DrawCommand command, String variantKey, CompletableFuture<TriangleMesh> preparation,
                              Matrix4x4d modelMatrix, double offsetX, double offsetY, double offsetZ,
                              double relativeScale, double heightExaggeration,
                              boolean imageryVisible, float[] boundsColor) {
}
