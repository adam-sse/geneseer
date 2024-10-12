package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import net.ssehub.program_repair.geneseer.evaluation.Evaluator;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.genetic.GeneticAlgorithm;
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
        options.addAll(Configuration.INSTANCE.getCliOptions());
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
        
        
        Configuration.INSTANCE.loadFromCli(cli);
        Configuration.INSTANCE.log();
        
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
        
        Map<String, Object> result = new HashMap<>();
        boolean oom = false;
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute(),
                    project.getEncoding());
            JunitEvaluation junit = new JunitEvaluation(project.getProjectDirectory(),
                    project.getTestExecutionClassPathAbsolute(), project.getEncoding());
            Evaluator evaluator = new Evaluator(project, compiler, junit, tempDirManager);
            
            LlmFixer llmFixer = null;
            if (Configuration.INSTANCE.genetic().llmMutationProbability() > 0.0) {
                llmFixer = PureLlmFixer.createLlmFixer(project, tempDirManager);
            }
            
            new GeneticAlgorithm(project, evaluator, llmFixer, tempDirManager).run(result);
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result.put("result", "IO_EXCEPTION");
            result.put("exception", e.getMessage());
            
        } catch (OutOfMemoryError e) {
            System.out.println("{\"result\":\"OUT_OF_MEMORY\"}");
            oom = true;
            throw e;
            
        } finally {
            if (!oom) {
                LOG.info("Timing measurements:");
                Map<String, Object> timings = new HashMap<>();
                StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                        .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                        .forEach(e -> {
                            LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms");
                            timings.put(e.getKey(), e.getValue());
                        });
                result.put("timings", timings);
                System.out.println(JsonUtils.GSON.toJson(result));
            }
        }
    }
    
}
