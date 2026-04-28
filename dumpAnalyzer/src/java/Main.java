package dumpanalyzer;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static final Path OUTPUT_ROOT = Paths.get("/tmp/output");

    public static void main(String[] args) {
        FunctionCounter counter = new FunctionCounter();
        GlTraceProcessor processor = new GlTraceProcessor(counter);
        FrameScanner scanner = new FrameScanner(OUTPUT_ROOT, processor::processFrame);

        scanner.scan();
        counter.printSorted();
    }
}
