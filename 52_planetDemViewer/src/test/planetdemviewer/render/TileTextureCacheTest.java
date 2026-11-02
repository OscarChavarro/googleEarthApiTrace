package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vsdk.toolkit.media.RGBAImageUncompressed;

class TileTextureCacheTest {
    private static final RGBAImageUncompressed IMAGE = new RGBAImageUncompressed();

    static {
        IMAGE.init(1, 1);
    }

    private static final class FakeGpu implements TextureUploader<Object> {
        final List<Object> destroyed = new ArrayList<>();
        int uploads;

        @Override
        public Object upload(RGBAImageUncompressed image) {
            uploads++;
            return new Object();
        }

        @Override
        public void destroy(Object texture) {
            destroyed.add(texture);
        }
    }

    @Test
    void uploadsOnlyOnceTheImageIsReady() {
        TileTextureCache<String, Object> cache = new TileTextureCache<>(100);
        FakeGpu gpu = new FakeGpu();
        assertNull(cache.acquire("a", key -> null, gpu));
        assertEquals(0, gpu.uploads);
        Object texture = cache.acquire("a", key -> IMAGE, gpu);
        assertSame(texture, cache.acquire("a", key -> IMAGE, gpu));
        assertSame(texture, cache.peekResident("a"));
        assertEquals(1, gpu.uploads);
    }

    @Test
    void destroysWhatFallsOutOfTheBudget() {
        TileTextureCache<String, Object> cache = new TileTextureCache<>(4);
        FakeGpu gpu = new FakeGpu();
        Object first = cache.acquire("a", key -> IMAGE, gpu);
        cache.acquire("b", key -> IMAGE, gpu);
        assertEquals(List.of(first), gpu.destroyed);
        assertNull(cache.peekResident("a"));
    }

    @Test
    void destroysWhatIsReleased() {
        TileTextureCache<String, Object> cache = new TileTextureCache<>(100);
        FakeGpu gpu = new FakeGpu();
        Object a = cache.acquire("a", key -> IMAGE, gpu);
        Object b = cache.acquire("b", key -> IMAGE, gpu);
        cache.release("a"::equals, gpu);
        assertEquals(List.of(a), gpu.destroyed);
        cache.clear(gpu);
        assertEquals(List.of(a, b), gpu.destroyed);
    }
}
