package dumpanalyzer.logger;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;

public final class ConcurrentMessages {
    public static final String LOG_POISON = "__LOG_POISON__";

    private ConcurrentMessages() {
    }

    public static Thread createLoggerThread(Path outputRoot, BlockingQueue<String> logQueue) {
        return new Thread(() -> {
            while (true) {
                String message = takeLogMessage(outputRoot, logQueue);
                if (LOG_POISON.equals(message)) {
                    return;
                }
                System.out.println(message);
            }
        }, "log-consumer");
    }

    public static void putLogMessage(Path outputRoot, BlockingQueue<String> logQueue, String message) {
        try {
            logQueue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while producing log queue");
        }
    }

    private static String takeLogMessage(Path outputRoot, BlockingQueue<String> logQueue) {
        try {
            return logQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FatalErrorHandler.fail(outputRoot, "Interrupted while consuming log queue");
            return LOG_POISON;
        }
    }
}
