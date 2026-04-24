package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import net.ssehub.program_repair.geneseer.Result.Patch;
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
    
    private static final Object OUTPUT_LOCK = new Object();
    private static volatile boolean resultPrinted = false;
    
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
        project.logConfiguration();
        
        Result result = new Result();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            printOutput(result, false); // no-op if printOutput() is called normally below
        }));
        
        boolean oom = false;
        try (Probe measure = Measurement.INSTANCE.start("total");
                TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            Node ast = new Parser().parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
            ast.lock();
            LOG.fine(() -> ast.stream().count() + " nodes in AST");
            
            result.astStats().setNodes((int) ast.stream().count());
            result.astStats().setStatements((int) ast.stream()
                    .filter(n -> n.getType() == Type.STATEMENT)
                    .count());
            
            TestSuite testSuite = new TestSuite(project, ast, tempDirManager, result.evaluationStats());
            result.astStats().setSuspicious((int) ast.stream()
                    .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                    .count());
            
            Node patched = createFixer(project, result, tempDirManager).run(ast, testSuite, result);
            if (patched != null) {
                analyzeDiffOfPatched(result, ast, patched, project.getEncoding(), tempDirManager);
            }
            
        } catch (EvaluationException | ParsingException e) {
            LOG.log(Level.SEVERE, "Original is unfit", e);
            result.setResult("ORIGINAL_UNFIT");
            result.setException(e.getClass().getName() + ": " + e.getMessage());
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result.setResult("IO_EXCEPTION");
            result.setException(e.getMessage());
            
        } catch (OutOfMemoryError e) {
            synchronized (OUTPUT_LOCK) {
                if (!resultPrinted) {
                    resultPrinted = true;
                    System.out.println("{\"result\":\"OUT_OF_MEMORY\"}");
                }
                oom = true;
                throw e;
            }
            
        } finally {
            if (!oom) {
                printOutput(result, true);
            }
        }
    }
    
    private static void printOutput(Result result, boolean normallyFinished) {
        synchronized (OUTPUT_LOCK) {
            if (!resultPrinted) {
                synchronized (result) {
                    addTimingsAndLogStats(result);
                    if (!normallyFinished) {
                        result.setResult("KILLED");
                    }
                    resultPrinted = true;
                    System.out.println(JsonUtils.toJson(result));
                }
            }
        }
    }

    private static IFixer createFixer(Project project, Result result, TemporaryDirectoryManager tempDirManager)
            throws IllegalArgumentException {
        IFixer fixer;
        switch (Configuration.INSTANCE.setup().fixer()) {
        case "GENETIC_ALGORITHM":
            fixer = new GeneticAlgorithm(Configuration.INSTANCE.genetic().llmMutationProbability() > 0.0
                            ? createLlmFixer(project, result, tempDirManager)
                            : null);
            break;
        case "LLM_SINGLE":
            fixer = new SingleLlm(createLlmFixer(project, result, tempDirManager));
            break;
        case "SETUP_TEST":
            fixer = new SetupTest();
            break;
        case "ONLY_DELETE":
            fixer = new OnlyDelete();
            break;
        case "LLM_QUERY_ANALYSIS":
            fixer = new LlmQueryAnalysis(project.getProjectDirectory(),
                    createLlmFixer(project, result, tempDirManager));
            break;
        case "OUTLINER": {
            Outliner outliner = new Outliner(project.getProjectDirectory(), project.getSourceDirectoryAbsolute(),
                    project.getEncoding());
            if (!Configuration.INSTANCE.llm().model().equals("dummy")) {
                outliner.setLlm(LlmFactory.fromConfiguration(Configuration.INSTANCE.llm()).create());
            }
            fixer = outliner;
            break;
        }
            
        default:
            throw new IllegalArgumentException("Unknown fixer name: " + Configuration.INSTANCE.setup().fixer());
        }
        return fixer;
    }
    
    private static LlmFixer createLlmFixer(Project project, Result result, TemporaryDirectoryManager tempDirManager)
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
        llmFixer.setLlmStats(result.llmStats());
        
        return llmFixer;
    }
    
    private static void analyzeDiffOfPatched(Result result, Node original, Node patched, Charset encoding,
            TemporaryDirectoryManager tempDirManager) throws IOException {
        Patch patch = result.patch();
        
        String diff = AstDiff.getDiff(original, patched, tempDirManager, encoding);
        
        LOG.info(() -> "Final patch:\n" + diff);
        patch.setDiff(diff);
        
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
        
        patch.setAddedLines(countAdd);
        patch.setRemovedLines(countRemove);
    }

    private static void addTimingsAndLogStats(Result result) {
        LOG.info("Timing measurements:");
        StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                .forEach(e -> {
                    LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms");
                    result.putTiming(e.getKey(), e.getValue());
                });
        
        for (Level level : new Level[] {
            Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE, Level.FINER, Level.FINEST}) {
            
            result.putLogCount(level.getName(), LoggingConfiguration.getMessageCount(level));
        }
    }
    
}
