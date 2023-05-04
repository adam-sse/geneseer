package net.ssehub.program_repair.geneseer.evaluation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvaluationResult {

    private List<TestFailure> failures;

    private Map<String, ClassCoverage> classCoverage = new HashMap<>();
    
    public List<TestFailure> getFailures() {
        return failures;
    }
    
    void setFailures(List<TestFailure> failures) {
        this.failures = failures;
    }
    
    void addClassCoverage(ClassCoverage coverage) {
        this.classCoverage.put(coverage.className(), coverage);
    }
    
}
