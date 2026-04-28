package net.ssehub.program_repair.geneseer.fixers;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Result;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;

public class OnlyDelete implements IFixer {

    private static final Logger LOG = Logger.getLogger(OnlyDelete.class.getName());
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Result result) {
        List<Node> suspicious = ast.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .sorted(Node.DESCENDING_SUSPICIOUSNESS)
                .toList();
        
        int initialFailingTests = testSuite.getInitialFailingTestResults().size();
        int bestFailingTests = initialFailingTests;
        result.fitness().setOriginal(initialFailingTests);
        Node bestVariant = ast;
        
        for (Node toDelete : suspicious) {
            double suspiciousness = (double) toDelete.getMetadata(Metadata.SUSPICIOUSNESS);
            LOG.info("Deleting " + toDelete + " (suspiciousness: " + suspiciousness + ")");
            result.mutationStats().increaseDeletions();
            
            Node clone = ast.cheapClone(toDelete);
            toDelete = clone.findEquivalentPath(ast, toDelete);
            
            Node parent = clone.findParent(toDelete).get();
            parent.remove(toDelete);
            clone.lock();
            
            try {
                List<TestResult> evaluation = testSuite.evaluate(clone);
                
                int numFailingTests = (int) evaluation.stream().filter(TestResult::isFailure).count();
                LOG.info(() -> numFailingTests + " failing tests");
                if (numFailingTests < bestFailingTests) {
                    bestFailingTests = numFailingTests;
                    bestVariant = clone;
                    result.fitness().setBest(bestFailingTests);
                }
                if (numFailingTests == 0) {
                    break;
                }
                
            } catch (CompilationException e) {
                LOG.info("Doesn't compile");
                
            } catch (EvaluationException e) {
                LOG.log(Level.INFO, "Failed to evaluate", e);
            }
        }
        
        if (bestFailingTests == 0) {
            LOG.info(() -> "Result: Found full fix");
            result.setResult("FOUND_FIX");
        } else if (bestFailingTests < initialFailingTests) {
            int b = bestFailingTests;
            LOG.info(() -> "Result: Improved from " + initialFailingTests + " to " + b + " failing tests");
            result.setResult("IMPROVED");
        } else {
            LOG.info(() -> "Result: No improvement");
            result.setResult("NO_CHANGE");
        }
        
        return bestVariant;
    }

}
