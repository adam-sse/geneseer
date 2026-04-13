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

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.code.Parser;
import net.ssehub.program_repair.geneseer.code.ParsingException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.fixers.IFixer;
import net.ssehub.program_repair.geneseer.fixers.LlmQueryAnalysis;
import net.ssehub.program_repair.geneseer.fixers.OnlyDelete;
import net.ssehub.program_repair.geneseer.fixers.Outliner;
import net.ssehub.program_repair.geneseer.fixers.SetupTest;
import net.ssehub.program_repair.geneseer.fixers.SingleLlm;
import net.ssehub.program_repair.geneseer.fixers.genetic.GeneticAlgorithm;
import net.ssehub.program_repair.geneseer.llm.ILlm;
import net.ssehub.program_repair.geneseer.llm.ISnippetRanker;
import net.ssehub.program_repair.geneseer.llm.LlmBasedFileRanker;
import net.ssehub.program_repair.geneseer.llm.LlmFactory;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.llm.RagRanker;
import net.ssehub.program_repair.geneseer.llm.SuspiciousnessRanker;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.CliArguments;
import net.ssehub.program_repair.geneseer.util.JsonUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class Geneseer {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Geneseer.class.getName());
    
    public static void main(String[] args) {
        LOG.info(() -> "Geneseer " + VersionInfo.VERSION + " (" + VersionInfo.GIT_COMMIT
                + (VersionInfo.GIT_DIRTY ? " dirty" : "") + ")");
        
        Project project = initializeProjectsAndConfiguration(args);
        main(project);
    }

    private static Project initializeProjectsAndConfiguration(String[] args) {
        Set<String> options = new HashSet<>();
        options.addAll(Project.getCliOptions());
        options.addAll(Configuration.INSTANCE.getCliOptions());
        
        Project project = null;
        try {
            CliArguments cli = new CliArguments(args, options);
            project = Project.readFromCommandLine(cli);
            Configuration.INSTANCE.loadFromCli(cli);
            Configuration.INSTANCE.log();
            
        } catch (IllegalArgumentException e) {
            LOG.severe("Command line arguments invalid: " + e.getMessage());
            LOG.severe("Usage: " + Project.getCliUsage() + " " + Configuration.INSTANCE.getCliUsage());
            System.exit(1);
        }
        
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
        LOG.config("    additional compiler options: " + project.getAdditionalCompilerOptions());
        
        TestSuite testSuite = null;
        Map<String, Object> result = new HashMap<>();
        Map<String, Integer> evaluationStats = new HashMap<>();
        result.put("evaluation", evaluationStats);
        boolean oom = false;
        try (Probe measure = Measurement.INSTANCE.start("total");
                TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            Node ast = new Parser().parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
            ast.lock();
            LOG.fine(() -> ast.stream().count() + " nodes in AST");
            
            Map<String, Object> astStats = new HashMap<>();
            result.put("ast", astStats);
            astStats.put("nodes", ast.stream().count());
            astStats.put("statements", ast.stream()
                    .filter(n -> n.getType() == Type.STATEMENT)
                    .count());
            
            testSuite = new TestSuite(project, ast, tempDirManager);
            astStats.put("suspicious", ast.stream()
                    .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                    .count());
            evaluationStats.put("initialPassingTestCases", testSuite.getInitialPassingTestResults().size());
            evaluationStats.put("initialFailingTestCases", testSuite.getInitialFailingTestResults().size());
            
            Node patched = createFixer(project, tempDirManager).run(ast, testSuite, result);
            if (patched != null) {
                analyzeDiffOfPatched(result, ast, patched, project.getEncoding(), tempDirManager);
            }
            
        } catch (ParsingException e) {
            result.put("result", "ORIGINAL_UNFIT");
            result.put("exception", e.getCause().getClass().getName() + ": " + e.getCause().getMessage());
            
        } catch (EvaluationException e) {
            result.put("result", "ORIGINAL_UNFIT");
            result.put("exception", e.getClass().getName() + ": " + e.getMessage());
            
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
                    evaluationStats.put("compilations", testSuite.getNumCompilations());
                    evaluationStats.put("testSuiteRuns", testSuite.getNumTestSuiteRuns());
                }
                addTimingsAndLogStats(result);
                System.out.println(JsonUtils.toJson(result));
            }
        }
    }

    private static IFixer createFixer(Project project, TemporaryDirectoryManager tempDirManager)
            throws IllegalArgumentException {
        IFixer result;
        switch (Configuration.INSTANCE.setup().fixer()) {
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
            result = new LlmQueryAnalysis(project.getProjectDirectory(), createLlmFixer(project, tempDirManager));
            break;
        case "OUTLINER": {
            Outliner outliner =  new Outliner(project.getProjectDirectory(), project.getSourceDirectoryAbsolute(),
                    project.getEncoding());
            if (!Configuration.INSTANCE.llm().model().equals("dummy")) {
                outliner.setLlm(LlmFactory.fromConfiguration(Configuration.INSTANCE.llm()).create());
            }
            result = outliner;
            break;
        }
            
        default:
            throw new IllegalArgumentException("Unknown fixer name: " + Configuration.INSTANCE.setup().fixer());
        }
        return result;
    }
    
    private static LlmFixer createLlmFixer(Project project, TemporaryDirectoryManager tempDirManager)
            throws IllegalArgumentException {
        LlmFactory factory = LlmFactory.fromConfiguration(Configuration.INSTANCE.llm());
        ILlm llm = factory.create();
        
        ISnippetRanker ranker;
        switch (Configuration.INSTANCE.llm().codeContextSelection()) {
        case SUSPICIOUSNESS:
            ranker = new SuspiciousnessRanker(Configuration.INSTANCE.llm().maxCodeContext());
            break;
        case RAG:
            ranker = new RagRanker(project.getProjectDirectory(),
                    Configuration.INSTANCE.llm().maxCodeContext(),
                    Configuration.INSTANCE.rag().model(),
                    Configuration.INSTANCE.rag().api());
            break;
        case LLM:
            ranker = new LlmBasedFileRanker(llm);
            break;
        default:
            throw new IllegalArgumentException("Invalid code context selection: "
                    + Configuration.INSTANCE.llm().codeContextSelection());
        }
        
        LlmFixer llmFixer = new LlmFixer(llm, ranker, tempDirManager, project.getEncoding(),
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

    private static void addTimingsAndLogStats(Map<String, Object> result) {
        LOG.info("Timing measurements:");
        Map<String, Object> timings = new HashMap<>();
        StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                .forEach(e -> {
                    LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms");
                    timings.put(e.getKey(), e.getValue());
                });
        result.put("timings", timings);
        
        Map<String, Integer> logStats = new HashMap<>();
        logStats.put("SEVERE", LoggingConfiguration.getMessageCount(Level.SEVERE));
        logStats.put("WARNING", LoggingConfiguration.getMessageCount(Level.WARNING));
        logStats.put("INFO", LoggingConfiguration.getMessageCount(Level.INFO));
        logStats.put("CONFIG", LoggingConfiguration.getMessageCount(Level.CONFIG));
        logStats.put("FINE", LoggingConfiguration.getMessageCount(Level.FINE));
        logStats.put("FINER", LoggingConfiguration.getMessageCount(Level.FINER));
        logStats.put("FINEST", LoggingConfiguration.getMessageCount(Level.FINEST));
        result.put("logLines", logStats);
    }
    
}
