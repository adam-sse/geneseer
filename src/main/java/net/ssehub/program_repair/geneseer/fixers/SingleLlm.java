package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;

public class SingleLlm implements IFixer {

    private static final Logger LOG = Logger.getLogger(SingleLlm.class.getName());
    
    private LlmFixer llmFixer;
    
    public SingleLlm(LlmFixer llmFixer) {
        this.llmFixer = llmFixer;
    }
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) throws IOException {
        Optional<Node> modifiedAst = llmFixer.createVariant(ast,
                testSuite.getInitialTestResults().stream()
                    .filter(TestResult::isFailure)
                    .toList());
        
        Node patched;
        String resultString;
        if (modifiedAst.isPresent()) {
            patched = modifiedAst.get();
            try {
                List<TestResult> evaluation = testSuite.evaluate(patched);
                int failingTests = (int) evaluation.stream().filter(TestResult::isFailure).count();
                result.put("modifiedFailingTests", failingTests);
                
                int initialFailingTests = testSuite.getInitialFailingTestResults().size();
                if (failingTests == 0) {
                    resultString = "FOUND_FIX";
                } else if (failingTests < initialFailingTests) {
                    resultString = "IMPROVED";
                } else if (failingTests == initialFailingTests) {
                    resultString = "NO_CHANGE";
                } else {
                    resultString = "WORSENED";
                }
                
            } catch (CompilationException e) {
                resultString = "VARIANT_UNFIT";
            } catch (EvaluationException e) {
                LOG.log(Level.WARNING, "Failed to evaluate", e);
                resultString = "VARIANT_UNFIT";
            }
        } else {
            patched = null;
            resultString = "VARIANT_CREATION_FAILED";
        }
        
        result.put("result", resultString);
        
        return patched;
    }

}
