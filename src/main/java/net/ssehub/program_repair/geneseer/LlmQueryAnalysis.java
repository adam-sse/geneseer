package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
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
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class LlmQueryAnalysis {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }

    private static final Logger LOG = Logger.getLogger(LlmQueryAnalysis.class.getName());
    
    private Project project;
    
    private Evaluator evaluator;
    
    private TemporaryDirectoryManager tempDirManager;
    
    public LlmQueryAnalysis(Project project, Evaluator evaluator, TemporaryDirectoryManager tempDirManager) {
        this.project = project;
        this.evaluator = evaluator;
        evaluator.setLogCompilerOutput(true);
        this.tempDirManager = tempDirManager;
    }
    
    public String run() throws IOException {
        Node originalAst = Parser.parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
        originalAst.lock();
        LOG.fine(() -> originalAst.stream().count() + " nodes in AST");
        
        List<TestResult> originalFailingTests = evaluateAndMeasureSuspiciousness(originalAst);
        
        String result;
        if (originalFailingTests != null) {
            net.ssehub.program_repair.geneseer.llm.LlmQueryAnalysis analysis =
                    new net.ssehub.program_repair.geneseer.llm.LlmQueryAnalysis(
                    project.getEncoding(), project.getProjectDirectory());
            
            result = analysis.analyzeQueryForProject(originalAst, originalFailingTests);
        } else {
            result = "ORIGINAL_UNFIT";
        }
        
        return result;
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
        
        String result = null;
        boolean oom = false;
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute(),
                    project.getEncoding());
            JunitEvaluation junit = new JunitEvaluation(project.getProjectDirectory(),
                    project.getTestExecutionClassPathAbsolute(), project.getEncoding());
            Evaluator evaluator = new Evaluator(project, compiler, junit, tempDirManager);
            
            result = new LlmQueryAnalysis(project, evaluator, tempDirManager).run();
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result = "IO_EXCEPTION";
            
        } catch (OutOfMemoryError e) {
            System.out.println("OUT_OF_MEMORY");
            oom = true;
            throw e;
            
        } finally {
            if (!oom) {
                System.out.println(result);
            }
            LOG.info("Timing measurements:");
            StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                    .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                    .forEach(e -> LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms"));
        }
    }

}
