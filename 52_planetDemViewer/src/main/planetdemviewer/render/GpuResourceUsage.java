package planetdemviewer.render;

/** How much of a kind of resource a graphics backend currently holds on the GPU. */
public interface GpuResourceUsage {
    GpuResourceUsage NONE = new GpuResourceUsage() {
        @Override
        public int residentCount() {
            return 0;
        }

        @Override
        public long bytesAssigned() {
            return 0L;
        }
    };

    int residentCount();

    long bytesAssigned();
}
