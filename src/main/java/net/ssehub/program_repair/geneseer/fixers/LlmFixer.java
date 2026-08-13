package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Result;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.AbstractLlmMutator;

public class LlmFixer implements IFixer {

    private static final Logger LOG = Logger.getLogger(LlmFixer.class.getName());
    
    private AbstractLlmMutator llmMutator;
    
    public LlmFixer(AbstractLlmMutator llmMutator) {
        this.llmMutator = llmMutator;
    }
    
    @Override
    public boolean needsFaultLocalization() {
        return llmMutator.needsFaultLocalization();
    }
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Result result) throws IOException {
        int initialFailingTests = testSuite.getInitialFailingTestResults().size();
        result.fitness().setOriginal(initialFailingTests);
        
        int numQueries = Configuration.INSTANCE.llm().numQueries();
        int numFixed = 0;
        Node best = null;
        int bestFixedTests = testSuite.getInitialFailingTestResults().size();
        for (int i = 1; i <= numQueries; i++) {
            LOG.info("Running query " + i + " of " + numQueries);
            
            Optional<Node> modifiedAst = llmMutator.createVariant(ast, testSuite.getInitialFailingTestResults());
            if (modifiedAst.isPresent()) {
                Node patched = modifiedAst.get();
                try {
                    List<TestResult> evaluation = testSuite.evaluate(patched);
                    int failingTests = (int) evaluation.stream().filter(TestResult::isFailure).count();
                    LOG.info(() -> "Variant has " + failingTests + " failing test cases (original had "
                            + initialFailingTests + ")");
                    
                    if (failingTests < bestFixedTests) {
                        result.fitness().setBest(failingTests);
                        best = patched;
                        bestFixedTests = failingTests;
                    }
                    
                    if (failingTests == 0) {
                        numFixed++;
                    }
                    
                } catch (CompilationException e) {
                    LOG.info("Variant did not compile");
                } catch (EvaluationException e) {
                    LOG.log(Level.WARNING, "Failed to evaluate", e);
                }
            } else {
                LOG.info("Could not create a variant with LLM");
            }
        }
        
        result.setResult(numFixed + "/" + numQueries);
        
        return best;
    }

}
