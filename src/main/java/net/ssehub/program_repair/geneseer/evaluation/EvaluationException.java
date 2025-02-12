package net.ssehub.program_repair.geneseer.evaluation;


public class EvaluationException extends Exception {

    private static final long serialVersionUID = -4079760130679646450L;

    EvaluationException(String message, Throwable cause) {
        super(message, cause);
    }

    EvaluationException(String message) {
        super(message);
    }

}
