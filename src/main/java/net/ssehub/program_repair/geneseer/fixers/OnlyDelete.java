package net.ssehub.program_repair.geneseer.fixers;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;

public class OnlyDelete implements IFixer {

    private static final Logger LOG = Logger.getLogger(OnlyDelete.class.getName());
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) {
        List<Node> suspicious = ast.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .sorted((n1, n2) -> Double.compare((double) n2.getMetadata(Metadata.SUSPICIOUSNESS),
                        (double) n1.getMetadata(Metadata.SUSPICIOUSNESS)))
                .toList();
        
        int initialFailingTests = testSuite.getInitialFailingTestResults().size();
        int bestFailingTests = initialFailingTests;
        Node bestVariant = ast;
        Double bestSuspiciousness = null;
        
        int tested = 0;
        for (Node toDelete : suspicious) {
            double suspiciousness = (double) toDelete.getMetadata(Metadata.SUSPICIOUSNESS);
            LOG.info("Deleting " + toDelete + " (suspiciousness: " + suspiciousness + ")");
            tested++;
            
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
                    bestSuspiciousness = suspiciousness;
                    bestVariant = clone;
                }
                if (numFailingTests == 0) {
                    break;
                }
                
            } catch (EvaluationException e) {
                LOG.log(Level.INFO, "Failed to evaluate", e);
            }
        }
        
        result.put("bestFailingTests", bestFailingTests);
        result.put("bestSuspiciousness", bestSuspiciousness);
        result.put("tested", tested);
        
        if (bestFailingTests == 0) {
            LOG.info(() -> "Result: Found full fix");
            result.put("result", "FOUND_FIX");
        } else if (bestFailingTests < initialFailingTests) {
            int b = bestFailingTests;
            LOG.info(() -> "Result: Improved from " + initialFailingTests + " to " + b + " failing tests");
            result.put("result", "IMPROVED");
        } else {
            LOG.info(() -> "Result: No improvement");
            result.put("result", "NO_CHANGE");
        }
        
        return bestVariant;
    }

}
