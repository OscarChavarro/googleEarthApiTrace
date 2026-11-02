package planetdemviewer.render.jogl4.terrain;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import java.nio.IntBuffer;
import planetdemviewer.render.DirectBuffers;
import planetdemviewer.render.terrain.TerrainMeshBuffers;
import planetdemviewer.render.terrain.TerrainMeshUploader;

/**
 * The GPU side of the terrain mesh cache, whose handles are OpenGL vertex arrays and
 * buffers. It is valid while the GL context it was given is current.
 */
final class Jogl4TerrainMeshHelper implements TerrainMeshUploader<Jogl4TerrainMeshHelper.GpuMesh> {
    private final GL4 gl;

    Jogl4TerrainMeshHelper(GL4 gl) {
        this.gl = gl;
    }

    @Override
    public GpuMesh upload(TerrainMeshBuffers source) {
        float[] positions = source.positions();
        float[] normals = source.normals();
        float[] uvs = source.textureCoordinates();
        int[] indices = source.indices();

        int[] vao = new int[1];
        int[] buffers = new int[4];
        gl.glGenVertexArrays(1, vao, 0);
        gl.glGenBuffers(4, buffers, 0);
        gl.glBindVertexArray(vao[0]);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[0]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) positions.length * Float.BYTES,
            DirectBuffers.floats(positions), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0L);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[1]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) normals.length * Float.BYTES,
            DirectBuffers.floats(normals), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 3, GL.GL_FLOAT, false, 0, 0L);

        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffers[2]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) uvs.length * Float.BYTES,
            DirectBuffers.floats(uvs), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribPointer(2, 2, GL.GL_FLOAT, false, 0, 0L);

        gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, buffers[3]);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) indices.length * Integer.BYTES,
            DirectBuffers.ints(indices), GL.GL_STATIC_DRAW);

        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        GpuBounds bounds = uploadBounds(source.boundsLines());
        return new GpuMesh(vao[0], buffers[0], buffers[1], buffers[2], buffers[3], indices.length, bounds);
    }

    private GpuBounds uploadBounds(float[] positions) {
        int[] vao = new int[1];
        int[] vbo = new int[1];
        gl.glGenVertexArrays(1, vao, 0);
        gl.glGenBuffers(1, vbo, 0);
        gl.glBindVertexArray(vao[0]);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo[0]);
        gl.glBufferData(GL.GL_ARRAY_BUFFER, (long) positions.length * Float.BYTES,
            DirectBuffers.floats(positions), GL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, GL.GL_FLOAT, false, 0, 0L);
        gl.glBindVertexArray(0);
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
        return new GpuBounds(vao[0], vbo[0]);
    }

    @Override
    public void replaceTriangles(GpuMesh mesh, IntBuffer indices) {
        int count = indices.remaining();
        gl.glBindVertexArray(mesh.vaoId);
        gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, (long) count * Integer.BYTES, indices, GL.GL_DYNAMIC_DRAW);
        gl.glBindVertexArray(0);
        mesh.indexCount = count;
    }

    @Override
    public void destroy(GpuMesh mesh) {
        gl.glDeleteBuffers(4, new int[] {mesh.positionVboId, mesh.normalVboId, mesh.uvVboId, mesh.indexBufferId}, 0);
        gl.glDeleteVertexArrays(1, new int[] {mesh.vaoId}, 0);
        gl.glDeleteBuffers(1, new int[] {mesh.bounds.vboId}, 0);
        gl.glDeleteVertexArrays(1, new int[] {mesh.bounds.vaoId}, 0);
    }

    static final class GpuMesh {
        final int vaoId;
        final GpuBounds bounds;
        int indexCount;
        private final int positionVboId;
        private final int normalVboId;
        private final int uvVboId;
        private final int indexBufferId;

        private GpuMesh(int vaoId, int positionVboId, int normalVboId, int uvVboId, int indexBufferId,
                        int indexCount, GpuBounds bounds) {
            this.vaoId = vaoId;
            this.positionVboId = positionVboId;
            this.normalVboId = normalVboId;
            this.uvVboId = uvVboId;
            this.indexBufferId = indexBufferId;
            this.indexCount = indexCount;
            this.bounds = bounds;
        }
    }

    record GpuBounds(int vaoId, int vboId) {
    }
}
