package net.ssehub.program_repair.geneseer.evaluation;


public class TestExecutionException extends EvaluationException {

    private static final long serialVersionUID = -6581323643570310384L;

    public TestExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public TestExecutionException(String message) {
        super(message);
    }

}
