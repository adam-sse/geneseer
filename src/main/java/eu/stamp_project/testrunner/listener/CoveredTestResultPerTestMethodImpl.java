package eu.stamp_project.testrunner.listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import eu.stamp_project.testrunner.runner.Failure;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;

public class CoveredTestResultPerTestMethodImpl implements CoveredTestResultPerTestMethod {

    private Map<String, Coverage> coverage = new HashMap<>();
    
    private Set<String> passing = new HashSet<>();
    
    private Set<String> ignored = new HashSet<>();
    
    private Map<String, Failure> failures = new HashMap<>();
    
    @Override
    public Map<String, Coverage> getCoverageResultsMap() {
        return coverage;
    }
    
    @Override
    public Coverage getCoverageOf(String testMethodName) {
        return this.coverage.get(testMethodName);
    }
    
    public void addPassingTest(TestResult test) {
        this.passing.add(test.testClass() + "#" + test.testMethod());
    }
    
    public void addFailingTest(TestResult test) {
        this.failures.put(test.testClass() + "#" + test.testMethod(), new Failure(test));
    }
    
    public void addIgnored(String test) {
        this.ignored.add(test);
    }

    @Override
    public Set<String> getPassingTests() {
        return passing;
    }
    
    @Override
    public Set<String> getIgnoredTests() {
        return ignored;
    }

    @Override
    public Failure getFailureOf(String testMethodName) {
        return failures.get(testMethodName);
    }

}
