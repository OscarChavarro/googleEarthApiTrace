package planetdemviewer.render;

import java.util.function.Function;
import java.util.function.Predicate;
import vsdk.toolkit.media.RGBAImageUncompressed;

/** Decides which decoded tile images become textures and how long they stay on the GPU. */
public final class TileTextureCache<K, T> {
    private final GpuResourceCache<K, T> residents;

    public TileTextureCache(long budgetBytes) {
        residents = new GpuResourceCache<>(budgetBytes, 0);
    }

    /**
     * Returns the resident texture, or uploads the image that the source has ready.
     * Returns null while the source has none, so the caller can keep showing something else.
     */
    public T acquire(K key, Function<K, RGBAImageUncompressed> source, TextureUploader<T> gpu) {
        T cached = residents.get(key);
        if (cached != null) {
            return cached;
        }
        RGBAImageUncompressed image = source.apply(key);
        if (image == null) {
            return null;
        }
        T texture = gpu.upload(image);
        residents.put(key, texture, image.getRawImageSizeInBytes()).forEach(gpu::destroy);
        return texture;
    }

    /** Checks residency only; never triggers a load. */
    public T peekResident(K key) {
        return residents.get(key);
    }

    public void release(Predicate<K> keys, TextureUploader<T> gpu) {
        residents.removeIf(keys).forEach(gpu::destroy);
    }

    public void clear(TextureUploader<T> gpu) {
        residents.clear().forEach(gpu::destroy);
    }

    public GpuResourceUsage usage() {
        return residents;
    }
}
