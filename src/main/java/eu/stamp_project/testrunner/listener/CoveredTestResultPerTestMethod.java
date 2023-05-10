package eu.stamp_project.testrunner.listener;

import java.util.Map;
import java.util.Set;

import eu.stamp_project.testrunner.runner.Failure;

public interface CoveredTestResultPerTestMethod {

    public Map<String, Coverage> getCoverageResultsMap();
    
    public Coverage getCoverageOf(String testMethodName);
    
    public Set<String> getPassingTests();
    
    public Set<String> getIgnoredTests();
    
    public Failure getFailureOf(String testMethodName);
    
}
