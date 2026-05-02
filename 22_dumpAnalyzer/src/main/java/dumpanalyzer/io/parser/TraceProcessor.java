package dumpanalyzer.io.parser;

import dumpanalyzer.parser.GlTraceLexer;
import dumpanalyzer.parser.GlTraceParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.BlockingQueue;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.Frame;
import vsdk.toolkit.environment.Camera;

public class TraceProcessor {
    private final FunctionCounter functionCounter;

    public TraceProcessor(FunctionCounter functionCounter) {
        this.functionCounter = functionCounter;
    }

    public Frame processFrame(int frame, String filename, BlockingQueue<String> logQueue) {
        enqueueLog(logQueue, "Processing frame " + frame + " from file " + filename);
        Path filePath = Paths.get(filename).toAbsolutePath();

        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            FatalErrorHandler.fail(filePath, "Cannot read file: " + e.getMessage());
            return new Frame(frame, List.of(), null, null, null);
        }

        String normalized = LogicalLineProcessor.normalize(content);
        parseOrFail(filePath, normalized);
        functionCounter.addFromContent(normalized);

        List<dumpanalyzer.model.TileInstance> tiles = TilesProcessor.processFrameCalls(frame, normalized, filePath.getParent());
        dumpanalyzer.model.TileInstance lastTile = tiles.isEmpty() ? null : tiles.get(tiles.size() - 1);
        double[] projectionMatrix = lastTile == null ? null : lastTile.getProjectionMatrix();
        double[] modelViewMatrix = lastTile == null ? null : lastTile.getModelViewMatrix();
        if (projectionMatrix == null) {
            projectionMatrix = CameraProcessor.extractProjectionMatrix(normalized);
        }
        if (modelViewMatrix == null) {
            modelViewMatrix = CameraProcessor.extractModelViewMatrix(normalized);
        }
        Camera googleCamera = new Camera();
        googleCamera.setName("GoogleCamera");
        CameraProcessor.applyProjectionMatrixToCamera(googleCamera, projectionMatrix);
        CameraProcessor.applyModelViewMatrixToCamera(googleCamera, modelViewMatrix);
        return new Frame(
            frame,
            tiles,
            projectionMatrix,
            modelViewMatrix,
            googleCamera
        );
    }

    private static void enqueueLog(BlockingQueue<String> logQueue, String message) {
        try {
            logQueue.put(message);
        }
        catch (InterruptedException e) {
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
