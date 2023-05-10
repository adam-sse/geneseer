package net.ssehub.program_repair.geneseer.evaluation;

import java.util.List;

public class EvaluationResult {

    private List<TestResult> executedTests;

    public List<TestResult> getFailures() {
        return executedTests.stream().filter(TestResult::isFailure).toList();
    }
    
    public List<TestResult> getExecutedTests() {
        return executedTests;
    }
    
    void setExecutedTests(List<TestResult> executedTests) {
        this.executedTests = executedTests;
    }
    
}
