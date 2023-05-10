package eu.stamp_project.testrunner.runner;

import net.ssehub.program_repair.geneseer.evaluation.TestResult;

public class Failure {

    public final String stackTrace;
    
    public Failure(TestResult test) {
        this.stackTrace = test.failureStacktrace();
    }

}
