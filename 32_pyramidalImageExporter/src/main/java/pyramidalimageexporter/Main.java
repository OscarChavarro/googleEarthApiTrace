package pyramidalimageexporter;

import pyramidalimageexporter.diagnostics.PerformanceReport;

public class Main {
    public static void main(String[] args) {
        PerformanceReport.start(args);
        Throwable failure = null;
        try {
            new PyramidalImageExporterApplication().run(args);
        }
        catch (Throwable throwable) {
            failure = throwable;
            throw throwable;
        }
        finally {
            long elapsedNanos = PerformanceReport.finishAndWrite(failure);
            System.out.println(
                "pyramidalImageExporter: total elapsed time "
                    + PerformanceReport.formatDuration(elapsedNanos)
                    + "; performance report: " + PerformanceReport.REPORT_PATH
            );
        }
    }
}
