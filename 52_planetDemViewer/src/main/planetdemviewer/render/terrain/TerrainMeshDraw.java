package planetdemviewer.render.terrain;

import java.util.List;
import planetdemviewer.processing.DrawCommand;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;

/**
 * A terrain mesh ready to draw for one camera, with everything already on the GPU. T is the
 * texture handle of the graphics backend, and M its handle for an uploaded mesh. The texture
 * blocks are the parts of the mesh to fill, each one with its own color or with none.
 */
public record TerrainMeshDraw<T, M>(TerrainDrawItem item, M mesh, List<TerrainTextureBlock<T>> textureBlocks,
                                    Matrix4x4d modelViewProjection,
                                    Matrix4x4d modelInverseTranspose) {
    public static List<DrawCommand> commands(List<? extends TerrainMeshDraw<?, ?>> draws) {
        return draws.stream().map(draw -> draw.item().command()).toList();
    }
}
