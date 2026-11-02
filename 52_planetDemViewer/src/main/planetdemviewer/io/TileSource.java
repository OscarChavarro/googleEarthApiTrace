package planetdemviewer.io;

import java.util.concurrent.CompletionStage;
import planetdemviewer.spatial.TileAddress;

/**
 * Backend-neutral asynchronous tile I/O contract. Existing FABDEM reading is
 * adapted incrementally; imagery and vector importers use this shape later.
 */
public interface TileSource<T> extends AutoCloseable {
    boolean has(TileAddress address);

    CompletionStage<T> request(TileRequest request);

    @Override
    default void close() {
    }
}

