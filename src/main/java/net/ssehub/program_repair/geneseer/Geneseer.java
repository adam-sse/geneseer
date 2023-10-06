package net.ssehub.program_repair.geneseer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
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
            project = readProjectFromCommandLine(args);
        } catch (IllegalArgumentException e) {
            LOG.severe("Command line arguments invalid: " + e.getMessage());
            LOG.severe("Usage: <projectDirectory> <sourceDirectory> <compilationClasspath> <testExecutionClassPath> <testClassName>...");
            System.exit(1);
        }
        
        LOG.info("Project:");
        LOG.info("    base directory: " + project.getProjectDirectory());
        LOG.info("    source directory: " + project.getSourceDirectory());
        LOG.info("    compilation classpath: " + project.getCompilationClasspath());
        LOG.info("    test execution classpath: " + project.getTestExecutionClassPath());
        LOG.info("    test classes (" + project.getTestClassNames().size() + "): " + project.getTestClassNames());
        LOG.fine("Using encoding " + Configuration.INSTANCE.getEncoding());
        
        Result result = null;
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute());
            
            result = new GeneticAlgorithm(compiler, project, tempDirManager).run();
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result = Result.ioException(e);
            
        } finally {
            System.out.println(result != null ? result.toCsv() : "null");
            LOG.info("Timing measurements:");
            StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                    .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                    .forEach(e -> LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms"));
        }
    }
    
    private static Project readProjectFromCommandLine(String[] args) throws IllegalArgumentException {
        if (args.length < 5) {
            throw new IllegalArgumentException("Too few arguments supplied");
        }
        
        Path projectDirectory = Path.of(args[0]);
        Path sourceDirectory = Path.of(args[1]);
        List<Path> compilationClasspath = readClasspath(args[2]);
        List<Path> testExecutionClassPath = readClasspath(args[3]);
        List<String> testClassNames = Arrays.stream(args).skip(4).toList();
        
        return new Project(projectDirectory, sourceDirectory, compilationClasspath,
                testExecutionClassPath, testClassNames);
    }
    
    private static List<Path> readClasspath(String combined) {
        List<Path> classpath = new LinkedList<>();
        
        if (!combined.isEmpty()) {
            char seperator;
            if (combined.indexOf(':') != -1 && combined.indexOf(';') == -1) {
                seperator = ':';
            } else {
                seperator = File.pathSeparatorChar;
            }
            
            for (String element : combined.split(String.valueOf(seperator))) {
                classpath.add(Path.of(element));
            }
            
        }
        
        return classpath;
    }
    
}
