package net.ssehub.program_repair.geneseer.evaluation;

public class TestIntegrityException extends TestExecutionException {

    private static final long serialVersionUID = -1269121473202165386L;

    TestIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
    
    TestIntegrityException(String message) {
        super(message);
    }

}
