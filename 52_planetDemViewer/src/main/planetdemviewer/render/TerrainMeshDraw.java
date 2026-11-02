package planetdemviewer.render;

import java.util.List;
import planetdemviewer.processing.DrawCommand;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;

/**
 * A terrain mesh ready to draw for one camera, with everything already on the GPU. T is the
 * texture handle of the graphics backend, and M its handle for an uploaded mesh. The imagery
 * is null while the mesh shows none.
 */
public record TerrainMeshDraw<T, M>(TerrainDrawItem item, M mesh, T imagery, Matrix4x4d modelViewProjection,
                                    Matrix4x4d modelInverseTranspose) {
    public static List<DrawCommand> commands(List<? extends TerrainMeshDraw<?, ?>> draws) {
        return draws.stream().map(draw -> draw.item().command()).toList();
    }
}
