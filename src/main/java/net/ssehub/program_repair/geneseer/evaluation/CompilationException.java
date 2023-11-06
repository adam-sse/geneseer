package net.ssehub.program_repair.geneseer.evaluation;


public class CompilationException extends EvaluationException {

    private static final long serialVersionUID = 3307398205104653303L;

    public CompilationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public CompilationException(String message) {
        super(message);
    }

}
