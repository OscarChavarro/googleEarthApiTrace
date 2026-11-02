package planetdemviewer.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class LatestRequestQueueTest {
    @Test
    void prioritizesVisibleWorkOverPrefetch() throws Exception {
        LatestRequestQueue<String, String> queue = new LatestRequestQueue<>();
        queue.submit("prefetch", "prefetch", 1);
        queue.submit("visible", "visible", 100);

        assertEquals("visible", queue.take());
        assertEquals("prefetch", queue.take());
    }

    @Test
    void replacesAnObsoleteRequestForTheSameResource() throws Exception {
        LatestRequestQueue<String, String> queue = new LatestRequestQueue<>();
        queue.submit("tile", "old", 100);
        queue.submit("tile", "new", 100);

        assertEquals("new", queue.take());
        assertEquals(0, queue.size());
    }

    @Test
    void usesRecencyForEqualPriorities() throws Exception {
        LatestRequestQueue<String, String> queue = new LatestRequestQueue<>();
        queue.submit("first", "first", 10);
        queue.submit("latest", "latest", 10);

        assertEquals("latest", queue.take());
    }
}
