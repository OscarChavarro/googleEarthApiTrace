package dumpanalyzer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.BlockingQueue;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class GlTraceProcessor {
    private static final Pattern BIND_TEXTURE_PATTERN = Pattern.compile(
        "\\bglBindTexture\\s*\\(\\s*[^,]+,\\s*(\\d+)\\s*\\)"
    );
    private final FunctionCounter functionCounter;

    public GlTraceProcessor(FunctionCounter functionCounter) {
        this.functionCounter = functionCounter;
    }

    public Frame processFrame(int frame, String filename, BlockingQueue<String> logQueue) {
        enqueueLog(logQueue, "Processing frame " + frame + " from file " + filename);
        Path filePath = Paths.get(filename).toAbsolutePath();

        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            FatalErrorHandler.fail(filePath, "Cannot read file: " + e.getMessage());
            return new Frame(frame);
        }

        String normalized = LogicalLineNormalizer.normalize(content);
        parseOrFail(filePath, normalized);
        functionCounter.addFromContent(normalized);

        return buildFrameFromGlTrace(frame, normalized);
    }

    private static Frame buildFrameFromGlTrace(int frameId, String normalizedContent) {
        Frame frame = new Frame(frameId);
        Set<Integer> ids = new LinkedHashSet<>();
        Matcher matcher = BIND_TEXTURE_PATTERN.matcher(normalizedContent);

        while (matcher.find()) {
            ids.add(Integer.parseInt(matcher.group(1)));
        }

        for (Integer contentId : ids) {
            frame.addTile(new TileInstance(contentId, null, null, null, null));
        }

        frame.sortTilesByContentId();
        return frame;
    }

    private static void enqueueLog(BlockingQueue<String> logQueue, String message) {
        try {
            logQueue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(Path.of("/tmp/output"), "Interrupted while queuing log message");
        }
    }

    private static void parseOrFail(Path filePath, String normalized) {
        GlTraceLexer lexer = new GlTraceLexer(CharStreams.fromString(normalized));
        GlTraceParser parser = new GlTraceParser(new CommonTokenStream(lexer));

        BaseErrorListener fatalListener = new BaseErrorListener() {
            @Override
            public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e
            ) {
                FatalErrorHandler.fail(filePath, "line " + line + ":" + charPositionInLine + " " + msg);
            }
        };

        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(fatalListener);
        parser.addErrorListener(fatalListener);

        parser.traceFile();
    }
}
