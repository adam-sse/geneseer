package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import net.ssehub.program_repair.geneseer.evaluation.Evaluator;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.genetic.GeneticAlgorithm;
import net.ssehub.program_repair.geneseer.genetic.Result;
import net.ssehub.program_repair.geneseer.llm.LlmConfiguration;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.CliArguments;
import net.ssehub.program_repair.geneseer.util.JsonUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class Geneseer {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Geneseer.class.getName());
    
    static Project initialize(String[] args) throws IOException {
        Set<String> options = new HashSet<>();
        options.addAll(Project.getCliOptions());
        options.add("--config");
        CliArguments cli = new CliArguments(args, options);
        
        Project project = null;
        try {
            project = Project.readFromCommandLine(cli);
        } catch (IllegalArgumentException e) {
            LOG.severe("Command line arguments invalid: " + e.getMessage());
            LOG.severe("Usage: " + "[--config <configuration file>] " + Project.getCliUsage());
            System.exit(1);
        }
        
        LOG.config("Project:");
        LOG.config("    base directory: " + project.getProjectDirectory());
        LOG.config("    source directory: " + project.getSourceDirectory());
        LOG.config("    compilation classpath: " + project.getCompilationClasspath());
        LOG.config("    test execution classpath: " + project.getTestExecutionClassPath());
        LOG.config("    test classes (" + project.getTestClassNames().size() + "): " + project.getTestClassNames());
        LOG.config("    encoding: " + project.getEncoding());
        
        if (cli.hasOption("--config")) {
            Path configFile = Path.of(cli.getOption("--config"));
            Configuration.INSTANCE.loadFromFile(configFile);
        }
        
        Configuration.INSTANCE.log();
        
        LlmConfiguration.INSTANCE.loadFromFile(Configuration.INSTANCE.getLlmConfigFile());
        LlmConfiguration.INSTANCE.log();
        
        return project;
    }
    
    public static void main(String[] args) {
        Project project = null;
        try {
            project = initialize(args);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read configuration file", e);
            System.exit(1);
        }
        
        Result result = null;
        boolean oom = false;
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute(),
                    project.getEncoding());
            JunitEvaluation junit = new JunitEvaluation(project.getProjectDirectory(),
                    project.getTestExecutionClassPathAbsolute(), project.getEncoding());
            Evaluator evaluator = new Evaluator(project, compiler, junit, tempDirManager);
            
            LlmFixer llmFixer = PureLlmFixer.createLlmFixer(project, tempDirManager);
            
            result = new GeneticAlgorithm(project, evaluator, llmFixer, tempDirManager).run();
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result = Result.ioException(e);
            
        } catch (OutOfMemoryError e) {
            System.out.println("{\"type\":\"OUT_OF_MEMORY\"}");
            oom = true;
            throw e;
            
        } finally {
            if (!oom) {
                System.out.println(JsonUtils.GSON.toJson(result));
            }
            LOG.info("Timing measurements:");
            StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                    .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                    .forEach(e -> LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms"));
        }
    }
    
}
