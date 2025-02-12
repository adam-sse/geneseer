package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.fixers.IFixer;
import net.ssehub.program_repair.geneseer.fixers.LlmQueryAnalysis;
import net.ssehub.program_repair.geneseer.fixers.OnlyDelete;
import net.ssehub.program_repair.geneseer.fixers.SingleLlm;
import net.ssehub.program_repair.geneseer.fixers.SetupTest;
import net.ssehub.program_repair.geneseer.fixers.genetic.GeneticAlgorithm;
import net.ssehub.program_repair.geneseer.llm.ChatGptConnection;
import net.ssehub.program_repair.geneseer.llm.DummyChatGptConnection;
import net.ssehub.program_repair.geneseer.llm.IChatGptConnection;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.CliArguments;
import net.ssehub.program_repair.geneseer.util.JsonUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class Geneseer {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Geneseer.class.getName());
    
    public static void main(String[] args) {
        Project project = null;
        try {
            project = initializeProjectsAndConfiguration(args);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read configuration file", e);
            System.exit(1);
        }
        
        main(project);
    }

    private static Project initializeProjectsAndConfiguration(String[] args) throws IOException {
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
        
        Configuration.INSTANCE.loadFromCli(cli);
        Configuration.INSTANCE.log();
        
        return project;
    }
    
    public static void main(Project project) {
        LOG.config("Project:");
        LOG.config("    base directory: " + project.getProjectDirectory());
        LOG.config("    source directory: " + project.getSourceDirectory());
        LOG.config("    compilation classpath: " + project.getCompilationClasspath());
        LOG.config("    test execution classpath: " + project.getTestExecutionClassPath());
        LOG.config("    test classes (" + project.getTestClassNames().size() + "): " + project.getTestClassNames());
        LOG.config("    encoding: " + project.getEncoding());
        
        TestSuite testSuite = null;
        Map<String, Object> result = new HashMap<>();
        boolean oom = false;
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            Node ast = Parser.parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
            ast.lock();
            LOG.fine(() -> ast.stream().count() + " nodes in AST");
            
            Map<String, Object> astStats = new HashMap<>();
            result.put("ast", astStats);
            astStats.put("nodes", ast.stream().count());
            
            testSuite = new TestSuite(project, ast, tempDirManager);
            astStats.put("suspicious", ast.stream()
                    .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                    .count());
            
            Node patched = createFixer(project, tempDirManager).run(ast, testSuite, result);
            if (patched != null) {
                analyzeDiffOfPatched(result, ast, patched, project.getEncoding(), tempDirManager);
            }
            
        } catch (EvaluationException e) {
            result.put("result", "ORIGINAL_UNFIT");
            result.put("exception", e.getMessage());
            
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
                if (testSuite != null) {
                    Map<String, Integer> evaluationStats = new HashMap<>();
                    evaluationStats.put("compilations", testSuite.getNumCompilations());
                    evaluationStats.put("testSuiteRuns", testSuite.getNumTestSuiteRuns());
                    result.put("evaluations", evaluationStats);
                }
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
    
    private static IFixer createFixer(Project project, TemporaryDirectoryManager tempDirManager) {
        IFixer result;
        switch (Configuration.INSTANCE.setup().getFixer()) {
        case "GENETIC_ALGORITHM":
            result = new GeneticAlgorithm(Configuration.INSTANCE.genetic().llmMutationProbability() > 0.0
                            ? createLlmFixer(project, tempDirManager)
                            : null);
            break;
        case "LLM_SINGLE":
            result = new SingleLlm(createLlmFixer(project, tempDirManager));
            break;
        case "SETUP_TEST":
            result = new SetupTest();
            break;
        case "ONLY_DELETE":
            result = new OnlyDelete();
            break;
        case "LLM_QUERY_ANALYSIS":
            result = new LlmQueryAnalysis(project.getEncoding(), project.getProjectDirectory());
            break;
            
        default:
            throw new IllegalArgumentException("Unknown fixer name: " + Configuration.INSTANCE.setup().getFixer());
        }
        return result;
    }
    
    private static LlmFixer createLlmFixer(Project project, TemporaryDirectoryManager tempDirManager) {
        IChatGptConnection chatGpt;
        if (Configuration.INSTANCE.llm().model().equals("dummy")) {
            LOG.warning("llm.model is set to \"dummy\"; not using a real LLM");
            chatGpt = new DummyChatGptConnection();
        } else {
            chatGpt = new ChatGptConnection(Configuration.INSTANCE.llm().apiUrl());
            if (Configuration.INSTANCE.llm().apiToken() != null) {
                ((ChatGptConnection) chatGpt).setToken(Configuration.INSTANCE.llm().apiToken());
            }
            if (Configuration.INSTANCE.llm().apiUserHeader() != null) {
                ((ChatGptConnection) chatGpt).setUserHeader(Configuration.INSTANCE.llm().apiUserHeader());
            }
        }
        
        LlmFixer llmFixer = new LlmFixer(chatGpt, tempDirManager, project.getEncoding(),
                project.getProjectDirectory());
        
        return llmFixer;
    }
    
    @SuppressWarnings("unchecked")
    private static void analyzeDiffOfPatched(Map<String, Object> result, Node original, Node patched, Charset encoding,
            TemporaryDirectoryManager tempDirManager) throws IOException {
        Map<String, Object> diffResult;
        if (result.containsKey("patch")) {
            diffResult = (Map<String, Object>) result.get("patch");
        } else {
            diffResult = new HashMap<>();
        }
        result.put("patch", diffResult);
        
        String diff = AstDiff.getDiff(original, patched, tempDirManager, encoding);
        
        LOG.info(() -> "Final patch:\n" + diff);
        diffResult.put("diff", diff);
        
        String[] difflines = diff.split("\n");
        int i;
        for (i = 0; i < difflines.length; i++) {
            if (difflines[i].startsWith("@@")) {
                break;
            }
        }
        
        int countAdd = 0;
        int countRemove = 0;
        for (; i < difflines.length; i++) {
            String l = difflines[i];
            if (l.startsWith("+") && !l.substring(1).isBlank()) {
                countAdd++;
            } else if (l.startsWith("-") && !l.substring(1).isBlank()) {
                countRemove++;
            } else if (l.startsWith("diff --git ")) {
                for (; i < difflines.length; i++) {
                    if (difflines[i].startsWith("@@")) {
                        break;
                    }
                }
            }
        }
        diffResult.put("addedLines", countAdd);
        diffResult.put("removedLines", countRemove);
    }
    
}
