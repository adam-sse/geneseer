package net.ssehub.program_repair.geneseer.evaluation;


public class CompilationException extends EvaluationException {

    private static final long serialVersionUID = 3307398205104653303L;

    CompilationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    CompilationException(String message) {
        super(message);
    }

}
