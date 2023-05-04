package net.ssehub.program_repair.geneseer.evaluation;

import java.util.List;

public class EvaluationResult {

    private List<TestFailure> failures;

    public List<TestFailure> getFailures() {
        return failures;
    }
    
    void setFailures(List<TestFailure> failures) {
        this.failures = failures;
    }
    
}
