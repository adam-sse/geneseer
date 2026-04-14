package net.ssehub.program_repair.geneseer.evaluation;

import java.io.Serializable;

public record TestResult(String testClass, String testMethod, String failureMessage, String failureStacktrace)
        implements Serializable {
    
    private static final long serialVersionUID = 5281136086896771809L;
    
    public boolean isFailure() {
        return failureStacktrace != null;
    }
    
    public boolean isTimeout() {
        return testMethod.equals("<none>") && failureMessage.equals("Timeout") && failureStacktrace.equals("Timeout");
    }
    
    @Override
    public String toString() {
        return testClass + "::" + testMethod;
    }

}
