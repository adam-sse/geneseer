package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.Evaluator;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.evaluation.TestExecutionException;
import net.ssehub.program_repair.geneseer.evaluation.fault_localization.FaultLocalization;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class OnlyDelete {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Geneseer.class.getName());

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
            
            result = run(project, evaluator, tempDirManager);
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            result = "IO_EXCEPTION;" + e.getMessage();
            
        } catch (OutOfMemoryError e) {
            System.out.println("OUT_OF_MEMORY");
            oom = true;
            throw e;
            
        } finally {
            if (!oom) {
                System.out.println(result != null ? result : "null");
            }
            LOG.info("Timing measurements:");
            StreamSupport.stream(Measurement.INSTANCE.finishedProbes().spliterator(), false)
                    .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
                    .forEach(e -> LOG.info(() -> "    " + e.getKey() + ": " + e.getValue() + " ms"));
        }
    }
    
    private static String run(Project project, Evaluator evaluator, TemporaryDirectoryManager tempDirManager) {
        String result;
        
        try {
            Node original = Parser.parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
            original.lock();
            LOG.fine(() -> original.stream().count() + " nodes in AST");
            
            evaluator.setLogCompilerOutput(true);
            evaluator.setKeepBinDirectory(true);
            EvaluationResult evaluation = evaluator.evaluate(original);
            Path binDir = evaluator.getLastBinDirectory();
            evaluator.setLogCompilerOutput(false);
            evaluator.setKeepBinDirectory(false);
            
            FaultLocalization faultLocalization = new FaultLocalization(project.getProjectDirectory(),
                    project.getTestExecutionClassPathAbsolute(), project.getEncoding());
            faultLocalization.measureAndAnnotateSuspiciousness(original, binDir, evaluation.getExecutedTests());
            
            int originalFailingTests = evaluation.getFailures().size();
            LOG.info(() -> originalFailingTests + " failing tests in original");
            result = runThroughSuspicious(original, originalFailingTests, evaluator);
            
        } catch (CompilationException e) {
            LOG.log(Level.SEVERE, "Failed compilation of original", e);
            result = "ORIGINAL_UNFIT";
            
        } catch (TestExecutionException e) {
            LOG.log(Level.SEVERE, "Failed running tests on original", e);
            result = "ORIGINAL_UNFIT";
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed parsing original code", e);
            result = "ORIGINAL_UNFIT";
        }
        
        return result;
    }
    
    private static String runThroughSuspicious(Node original, int originalFailingTests, Evaluator evaluator) {
        List<Node> suspicious = original.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .sorted((n1, n2) -> Double.compare((double) n2.getMetadata(Metadata.SUSPICIOUSNESS),
                        (double) n1.getMetadata(Metadata.SUSPICIOUSNESS)))
                .toList();
        
        int best = originalFailingTests;
        
        for (Node toDelete : suspicious) {
            LOG.info("Deleting " + toDelete);
            
            Node clone = original.cheapClone(toDelete);
            toDelete = clone.findEquivalentPath(original, toDelete);
            
            Node parent = clone.findParent(toDelete).get();
            parent.remove(toDelete);
            clone.lock();
            
            try {
                EvaluationResult evaluation = evaluator.evaluate(clone);
                
                int numFailingTests = evaluation.getFailures().size();
                LOG.info(() -> numFailingTests + " failing tests");
                if (numFailingTests < originalFailingTests) {
                    best = numFailingTests;
                }
                if (numFailingTests == 0) {
                    break;
                }
                
            } catch (CompilationException e) {
                LOG.info("Failed to compile");
            } catch (TestExecutionException e) {
                LOG.log(Level.WARNING, "Failed running tests", e);
            }
        }
        
        String result;
        if (best == 0) {
            LOG.info(() -> "Result: Found full fix");
            result = "FULL_FIX;" + originalFailingTests + ";0";
        } else if (best < originalFailingTests) {
            int b = best;
            LOG.info(() -> "Result: Improved from " + originalFailingTests + " to " + b + " failing tests");
            result = "IMPROVED;" + originalFailingTests + ";" + best;
        } else {
            LOG.info(() -> "Result: No improvement");
            result = "NO_CHANGE";
        }
        return result;
    }
    
}
