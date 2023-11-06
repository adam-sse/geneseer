package net.ssehub.program_repair.geneseer.evaluation;


public class TestTimeoutException extends TestExecutionException {

    private static final long serialVersionUID = 2350705615071892568L;

    public TestTimeoutException(String message) {
        super(message);
    }

}
