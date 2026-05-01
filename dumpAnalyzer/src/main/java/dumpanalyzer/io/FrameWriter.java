package dumpanalyzer.io;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.Frame;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class FrameWriter {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter JSON_PRETTY_PRINTER = createPrettyPrinter();

    private FrameWriter() {
    }

    public static void writeFrames(Path outputRoot, List<Frame> frames) {
        for (Frame frame : frames) {
            Path frameDir = outputRoot.resolve(String.format("%05d", frame.getId()));
            Path frameFile = frameDir.resolve("frame.json");
            try {
                JSON_MAPPER.writer(JSON_PRETTY_PRINTER).writeValue(frameFile.toFile(), frame);
            } catch (IOException e) {
                FatalErrorHandler.fail(frameFile, "Cannot write frame file: " + e.getMessage());
            }
        }
    }

    private static DefaultPrettyPrinter createPrettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", System.lineSeparator());
        printer = printer.withArrayIndenter(indenter);
        printer = printer.withObjectIndenter(indenter);
        return printer;
    }
}
