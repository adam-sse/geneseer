package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import net.ssehub.program_repair.geneseer.IFixer;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;

public class SingleLlm implements IFixer {

    private LlmFixer llmFixer;
    
    public SingleLlm(LlmFixer llmFixer) {
        this.llmFixer = llmFixer;
    }
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) throws IOException {
        Optional<Node> modifiedAst = llmFixer.createVariant(ast, testSuite.getOriginalTestResults().stream()
                .filter(TestResult::isFailure)
                .toList());
        int originalFailingTests = testSuite.getOriginalNegativeTestNames().size();
        result.put("originalFailingTests", originalFailingTests);
        
        Node patched;
        String resultString;
        if (modifiedAst.isPresent()) {
            patched = modifiedAst.get();
            try {
                EvaluationResult evaluation = testSuite.evaluate(patched);
                int failingTests = evaluation.getFailures().size();
                result.put("modifiedFailingTests", failingTests);
                
                if (failingTests == 0) {
                    resultString = "FOUND_FIX";
                } else if (failingTests < originalFailingTests) {
                    resultString = "IMPROVED";
                } else if (failingTests == originalFailingTests) {
                    resultString = "NO_CHANGE";
                } else {
                    resultString = "WORSENED";
                }
                
            } catch (EvaluationException e) {
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
