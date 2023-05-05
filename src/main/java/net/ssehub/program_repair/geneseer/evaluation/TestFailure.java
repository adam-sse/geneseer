package net.ssehub.program_repair.geneseer.evaluation;

import java.io.Serializable;

import eu.stamp_project.testrunner.runner.Failure;

public record TestFailure(String testClass, String testMethod, String message, String stacktrace)
        implements Serializable {
    
    private static final long serialVersionUID = 349718598568296233L;
    
    TestFailure(Failure failure) {
        this(failure.testClassName, extractMethodName(failure.testCaseName), failure.messageOfFailure, failure.stackTrace);
    }
    
    private static String extractMethodName(String fullTestCase) {
        int index = fullTestCase.indexOf('#');
        return fullTestCase.substring(index + 1);
    }
    
    @Override
    public String toString() {
        return testClass + "::" + testMethod;
    }

}
