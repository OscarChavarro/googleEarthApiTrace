package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;

class DirectBuffersTest {
    @Test
    void floatsAreReadyToReadInNativeMemory() {
        FloatBuffer buffer = DirectBuffers.floats(new float[] {1f, 2f});
        assertTrue(buffer.isDirect());
        assertEquals(ByteOrder.nativeOrder(), buffer.order());
        assertEquals(0, buffer.position());
        assertEquals(2, buffer.remaining());
        assertEquals(2f, buffer.get(1));
    }

    @Test
    void anIntBufferIsReusedOnlyWhileTheDataFits() {
        IntBuffer large = DirectBuffers.ints(new int[] {1, 2, 3});
        IntBuffer reused = DirectBuffers.ints(new int[] {4}, large);
        assertSame(large, reused);
        assertEquals(0, reused.position());
        assertEquals(1, reused.remaining());
        assertEquals(4, reused.get(0));
        IntBuffer grown = DirectBuffers.ints(new int[] {5, 6, 7, 8}, reused);
        assertNotSame(reused, grown);
        assertEquals(4, grown.remaining());
    }
}
