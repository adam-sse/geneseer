package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.Evaluator;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.evaluation.TestExecutionException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.fault_localization.FaultLocalization;
import net.ssehub.program_repair.geneseer.llm.ChatGptConnection;
import net.ssehub.program_repair.geneseer.llm.DummyChatGptConnection;
import net.ssehub.program_repair.geneseer.llm.IChatGptConnection;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.util.JsonUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class PureLlmFixer {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }

    private static final Logger LOG = Logger.getLogger(PureLlmFixer.class.getName());
    
    private Project project;
    
    private Evaluator evaluator;
    
    private TemporaryDirectoryManager tempDirManager;
    
    public PureLlmFixer(Project project, Evaluator evaluator, TemporaryDirectoryManager tempDirManager) {
        this.project = project;
        this.evaluator = evaluator;
        evaluator.setLogCompilerOutput(true);
        this.tempDirManager = tempDirManager;
    }
    
    public Result run() throws IOException {
        Node originalAst = Parser.parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
        originalAst.lock();
        LOG.fine(() -> originalAst.stream().count() + " nodes in AST");
        
        List<TestResult> originalFailingTests = evaluateAndMeasureSuspiciousness(originalAst);
        
        Result result;
        if (originalFailingTests != null) {
            LlmFixer llmFixer = createLlmFixer(project, tempDirManager);
            Optional<Node> modifiedAst = llmFixer.createVariant(originalAst, originalFailingTests);
            
            if (modifiedAst.isPresent()) {
                int modifiedFailingTests = evaluate(modifiedAst.get());
                
                if (modifiedFailingTests == -1) {
                    result = new Result(Type.VARIANT_UNFIT, originalFailingTests.size(), null, null);
                } else if (modifiedFailingTests == 0) {
                    result = new Result(Type.FOUND_FIX, originalFailingTests.size(), modifiedFailingTests, null);
                } else if (modifiedFailingTests < originalFailingTests.size()) {
                    result = new Result(Type.IMPROVED, originalFailingTests.size(), modifiedFailingTests, null);
                } else if (modifiedFailingTests == originalFailingTests.size()) {
                    result = new Result(Type.NO_CHANGE, originalFailingTests.size(), modifiedFailingTests, null);
                } else {
                    result = new Result(Type.WORSENED, originalFailingTests.size(), modifiedFailingTests, null);
                }
                
            } else {
                result = new Result(Type.VARIANT_CREATION_FAILED, originalFailingTests.size(), null, null);
            }
            
        } else {
            result = new Result(Type.ORIGINAL_UNFIT, null, null, null);
        }
        
        return result;
    }
    
    private enum Type {
        VARIANT_UNFIT,
        FOUND_FIX,
        IMPROVED,
        NO_CHANGE,
        WORSENED,
        VARIANT_CREATION_FAILED,
        ORIGINAL_UNFIT,
        IO_EXCEPTION,
    }
    
    private static record Result(Type result, Integer originalFailingTests, Integer modifiedFailingTests,
            String ioException) {
        
    }
    
    static LlmFixer createLlmFixer(Project project, TemporaryDirectoryManager tempDirManager) {
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
    
    private int evaluate(Node ast) {
        int numFailingTests;
        
        try {
            EvaluationResult evaluation = evaluator.evaluate(ast);
            
            Set<String> negativeTests = new HashSet<>();
            Set<String> positiveTests = new HashSet<>();
            for (TestResult test : evaluation.getExecutedTests()) {
                if (test.isFailure()) {
                    negativeTests.add(test.toString());
                } else {
                    positiveTests.add(test.toString());
                }
            }
            
            numFailingTests = negativeTests.size();
            
            LOG.fine(() -> "Negative tests (" + negativeTests.size() + "): " + negativeTests);
            LOG.fine(() -> "Positive tests (" + positiveTests.size() + "): " + positiveTests);
            
            LOG.info(() -> "Variant has " + negativeTests.size() + " failing tests (" + positiveTests.size()
                    + " succeeding)");
            
        } catch (CompilationException e) {
            LOG.log(Level.SEVERE, "Failed compilation", e);
            numFailingTests = -1;
            
        } catch (TestExecutionException e) {
            LOG.log(Level.SEVERE, "Failed running tests", e);
            numFailingTests = -1;
        }
        
        return numFailingTests;
    }
    
    private List<TestResult> evaluateAndMeasureSuspiciousness(Node ast) {
        List<TestResult> failingTests;
        Path binDirectory = null;
        
        try {
            evaluator.setKeepBinDirectory(true);
            EvaluationResult evaluation = evaluator.evaluate(ast);
            binDirectory = evaluator.getLastBinDirectory();
            evaluator.setKeepBinDirectory(false);
            
            failingTests = new LinkedList<>();
            Set<String> positiveTests = new HashSet<>();
            for (TestResult test : evaluation.getExecutedTests()) {
                if (test.isFailure()) {
                    failingTests.add(test);
                } else {
                    positiveTests.add(test.toString());
                }
            }
            
            List<String> negativeTests = failingTests.stream().map(TestResult::toString).toList();
            LOG.fine(() -> "Negative tests (" + negativeTests.size() + "): " + negativeTests);
            LOG.fine(() -> "Positive tests (" + positiveTests.size() + "): " + positiveTests);
            
            LOG.info(() -> "Original has " + negativeTests.size() + " failing tests (" + positiveTests.size()
                    + " succeeding)");
            
            FaultLocalization faultLocalization = new FaultLocalization(project.getProjectDirectory(),
                    project.getTestExecutionClassPathAbsolute(), project.getEncoding());
            faultLocalization.measureAndAnnotateSuspiciousness(ast, binDirectory, evaluation.getExecutedTests());
            
        } catch (CompilationException e) {
            LOG.log(Level.SEVERE, "Failed compilation of unmodified original", e);
            failingTests = null;
            
        } catch (TestExecutionException e) {
            LOG.log(Level.SEVERE, "Failed running tests on unmodified original", e);
            failingTests = null;
        }
        
        try {
            if (binDirectory != null) {
                tempDirManager.deleteTemporaryDirectory(binDirectory);
            }
        } catch (IOException e) {
            // ignore, will be cleaned up later when tempDirManager is closed
        }
        
        return failingTests;
    }
    
    public static void main(String[] args) {
        Project project = null;
        try {
            project = Geneseer.initialize(args);
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
            
            result = new PureLlmFixer(project, evaluator, tempDirManager).run();
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result = new Result(Type.IO_EXCEPTION, null, null, e.getMessage());
            
        } catch (OutOfMemoryError e) {
            System.out.println("{\"result\":\"OUT_OF_MEMORY\"}");
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
