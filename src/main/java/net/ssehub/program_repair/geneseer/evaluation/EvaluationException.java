package net.ssehub.program_repair.geneseer.evaluation;


public class EvaluationException extends Exception {

    private static final long serialVersionUID = -4079760130679646450L;

    public EvaluationException(String message, Throwable cause) {
        super(message, cause);
    }

    public EvaluationException(String message) {
        super(message);
    }

    public EvaluationException(Throwable cause) {
        super(cause);
    }

}
