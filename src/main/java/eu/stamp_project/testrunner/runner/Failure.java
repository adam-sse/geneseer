package eu.stamp_project.testrunner.runner;

import net.ssehub.program_repair.geneseer.evaluation.TestResult;

public class Failure {

    // checkstyle: stop visibility modifier check
    public final String stackTrace;
    // checkstyle: resume visibility modifier check
    
    public Failure(TestResult test) {
        this.stackTrace = test.failureStacktrace();
    }

}
