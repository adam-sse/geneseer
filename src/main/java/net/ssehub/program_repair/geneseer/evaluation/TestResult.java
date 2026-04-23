package net.ssehub.program_repair.geneseer.evaluation;

import java.io.Serializable;

public record TestResult(
    String testClass,
    String implementingClass,
    String testMethod,
    String failureStacktrace
) implements Serializable {
    
    private static final long serialVersionUID = -4814081494206714329L;
    
    public boolean isFailure() {
        return failureStacktrace != null;
    }
    
    public boolean isTimeout() {
        return testMethod.equals("<none>") && failureStacktrace.equals("Timeout");
    }
    
    public String getIdentifier() {
        StringBuilder result = new StringBuilder();
        result.append(testClass).append("::").append(getMethodIdentifier());
        return result.toString();
    }
    
    public String getMethodIdentifier() {
        StringBuilder result = new StringBuilder(testMethod);
        if (!testClass.equals(implementingClass)) {
            result.append('@').append(implementingClass);
        }
        return result.toString();
    }
    
    @Override
    public String toString() {
        return getIdentifier();
    }

}
