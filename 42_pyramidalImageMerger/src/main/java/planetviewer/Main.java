package planetviewer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.merge.MergeAnalysis;
import planetviewer.merge.PyramidalImageMergeService;
import planetviewer.model.PlanetViewerModel;
import planetviewer.model.PyramidalImage;
import planetviewer.model.PyramidalImageInstance;
import planetviewer.model.QuadtreeNode;
import planetviewer.options.ArgumentParser;
import planetviewer.options.CliArguments;
import planetviewer.render.Jogl4PyramidalImageMergerRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

public class Main {
    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Optional<CliArguments> parsed = ArgumentParser.parse(args);
        if (parsed.isEmpty()) {
            ArgumentParser.printUsage();
            return 1;
        }
        CliArguments cliArguments = parsed.get();
        List<String> folders = cliArguments.getPyramidalImageFolders();

        String destinationFolder = folders.get(0);
        String deltaFolder = folders.get(1);
        PlanetViewerModel model = new PlanetViewerModel();
        PyramidalImage destination = loadRequiredImage(destinationFolder, "destination");
        PyramidalImage delta = loadRequiredImage(deltaFolder, "delta");
        if (destination == null || delta == null) {
            return 1;
        }

        if (cliArguments.isOffline()) {
            return runOfflineMerge(destination, delta, cliArguments.isDryRun());
        }

        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL.");
            return 1;
        }

        PyramidalImageInstance destinationInstance = model.addImage(destination);
        PyramidalImageInstance deltaInstance = model.addImage(delta);
        Jogl4PyramidalImageMergerRenderer renderer = new Jogl4PyramidalImageMergerRenderer(
            model,
            destinationInstance,
            deltaInstance
        );
        InteractiveViewer interactiveViewer = new InteractiveViewer(model, renderer);
        interactiveViewer.launchDesktop();
        return 0;
    }

    private static int runOfflineMerge(PyramidalImage destination, PyramidalImage delta, boolean dryRun) {
        System.out.println("Loaded destination pyramidal image " + destination.getSourceFolder()
            + ": " + destination.getTileCount() + " tiles, height " + destination.getHeight());
        System.out.println("Loaded delta pyramidal image " + delta.getSourceFolder()
            + ": " + delta.getTileCount() + " tiles, height " + delta.getHeight());

        try {
            PyramidalImageMergeService service = new PyramidalImageMergeService();
            MergeAnalysis analysis;
            if (dryRun) {
                analysis = service.validate(destination, delta);
            }
            else {
                analysis = service.validateAndMerge(destination, delta).analysis();
            }
            System.out.println(analysis.summary());
            if (!analysis.isMergePossible()) {
                printConflictDetails(destination, delta, analysis);
                return 2;
            }
            if (dryRun) {
                System.out.println("Dry run completed. Merge is possible; destination was not modified.");
            }
            else {
                System.out.println(analysis.mergeCompletedSummary());
            }
            return 0;
        }
        catch (IOException ex) {
            System.err.println("ERROR: Merge stopped: " + ex.getMessage());
            return 1;
        }
    }

    private static PyramidalImage loadRequiredImage(String folder, String role) {
        PyramidalImageFolderReader reader = new PyramidalImageFolderReader();
        Path path = Path.of(folder).toAbsolutePath().normalize();
        Optional<PyramidalImage> image = reader.read(path);
        if (image.isEmpty()) {
            System.err.println("Invalid " + role + " pyramidal image folder (missing 0.png or unreadable tile tree): " + path);
            ArgumentParser.printUsage();
            return null;
        }
        return image.get();
    }

    private static void printConflictDetails(PyramidalImage destination, PyramidalImage delta, MergeAnalysis analysis) {
        System.out.println("Conflict details:");
        for (String nodeId : analysis.getConflictingNodeIds()) {
            QuadtreeNode destinationNode = findNode(destination.getRoot(), nodeId);
            QuadtreeNode deltaNode = findNode(delta.getRoot(), nodeId);
            String reason = analysis.getConflictDetails().getOrDefault(nodeId, "files differ");
            System.out.println("- " + nodeId + ": " + reason);
            if (destinationNode != null && destinationNode.getTileFile() != null) {
                System.out.println("  destination: " + destinationNode.getTileFile().getAbsolutePath());
            }
            if (deltaNode != null && deltaNode.getTileFile() != null) {
                System.out.println("  delta: " + deltaNode.getTileFile().getAbsolutePath());
            }
        }
    }

    private static QuadtreeNode findNode(QuadtreeNode node, String id) {
        if (node == null) {
            return null;
        }
        if (node.getId().equals(id)) {
            return node;
        }
        if (!node.hasChildren()) {
            return null;
        }
        for (QuadtreeNode child : node.getChildren()) {
            QuadtreeNode found = findNode(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
