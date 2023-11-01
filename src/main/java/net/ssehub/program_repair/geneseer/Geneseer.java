package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.genetic.GeneticAlgorithm;
import net.ssehub.program_repair.geneseer.genetic.Result;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class Geneseer {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Geneseer.class.getName());
    
    public static void main(String[] args) throws IOException {
        Project project = null;
        try {
            project = Project.readFromCommandLine(args);
        } catch (IllegalArgumentException e) {
            LOG.severe("Command line arguments invalid: " + e.getMessage());
            LOG.severe(Project.getCliUsage());
            System.exit(1);
        }
        
        LOG.config("Project:");
        LOG.config("    base directory: " + project.getProjectDirectory());
        LOG.config("    source directory: " + project.getSourceDirectory());
        LOG.config("    compilation classpath: " + project.getCompilationClasspath());
        LOG.config("    test execution classpath: " + project.getTestExecutionClassPath());
        LOG.config("    test classes (" + project.getTestClassNames().size() + "): " + project.getTestClassNames());
        LOG.config("    encoding: " + project.getEncoding());
        
        Result result = null;
        boolean oom = false;
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute(),
                    project.getEncoding());
            
            result = new GeneticAlgorithm(compiler, project, tempDirManager).run();
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result = Result.ioException(e);
            
        } catch (OutOfMemoryError e) {
            System.out.println("OUT_OF_MEMORY");
            oom = true;
            throw e;
            
        } finally {
            if (!oom) {
                System.out.println(result != null ? result.toCsv() : "null");
            }
            LOG.info("Timing measurements:");
            StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                    .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                    .forEach(e -> LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms"));
        }
    }
    
}
