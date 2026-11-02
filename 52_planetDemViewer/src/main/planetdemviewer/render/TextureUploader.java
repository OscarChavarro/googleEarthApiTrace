package planetdemviewer.render;

import vsdk.toolkit.media.RGBAImageUncompressed;

/** What a graphics backend does for the texture caches; T is its texture handle. */
public interface TextureUploader<T> {
    T upload(RGBAImageUncompressed image);

    void destroy(T texture);
}
