package planetdemviewer.render.terrain;

import java.nio.IntBuffer;

/** What a graphics backend does for the terrain mesh cache; M is its handle for an uploaded mesh. */
public interface TerrainMeshUploader<M> {
    M upload(TerrainMeshBuffers buffers);

    /** The buffer is only valid during the call. */
    void replaceTriangles(M mesh, IntBuffer indices);

    void destroy(M mesh);
}
