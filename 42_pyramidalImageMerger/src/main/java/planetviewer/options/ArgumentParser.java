package planetviewer.options;

public final class ArgumentParser {
    private ArgumentParser() {
    }

    public static void printUsage() {
        System.err.println(
            "Usage: gradle run --args=\"<destinationPyramidalImageFolder> <deltaPyramidalImageFolder>\""
        );
        System.err.println(
            "  destinationPyramidalImageFolder: root folder of the destination pyramidal image tree."
        );
        System.err.println(
            "  deltaPyramidalImageFolder: root folder of the delta pyramidal image tree to validate for merge."
        );
    }
}
