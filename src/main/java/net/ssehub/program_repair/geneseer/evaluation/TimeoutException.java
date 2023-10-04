package net.ssehub.program_repair.geneseer.evaluation;


public class TimeoutException extends EvaluationException {

    private static final long serialVersionUID = 2350705615071892568L;

    public TimeoutException(String message) {
        super(message);
    }

}
