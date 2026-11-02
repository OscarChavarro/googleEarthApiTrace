package planetdemviewer.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Native-memory copies of vertex data, which is the form graphics APIs read it in. */
public final class DirectBuffers {
    private DirectBuffers() {
    }

    public static FloatBuffer floats(float[] data) {
        FloatBuffer buffer = allocate(data.length * Float.BYTES).asFloatBuffer();
        buffer.put(data).flip();
        return buffer;
    }

    public static IntBuffer ints(int[] data) {
        return ints(data, null);
    }

    /** Reuses the given buffer when the data fits in it, for data that changes often. It may be null. */
    public static IntBuffer ints(int[] data, IntBuffer reusable) {
        IntBuffer buffer = reusable != null && reusable.capacity() >= data.length
            ? reusable
            : allocate(data.length * Integer.BYTES).asIntBuffer();
        buffer.clear();
        buffer.put(data).flip();
        return buffer;
    }

    private static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }
}
