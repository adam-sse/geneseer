package net.ssehub.program_repair.geneseer.evaluation;

public class TestCoverageException extends TestExecutionException {

    private static final long serialVersionUID = 3232217029600060865L;

    TestCoverageException(String message, Throwable cause) {
        super(message, cause);
    }
    
    TestCoverageException(String message) {
        super(message);
    }

}
