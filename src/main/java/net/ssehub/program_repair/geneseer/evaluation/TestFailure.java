package net.ssehub.program_repair.geneseer.evaluation;

import java.io.Serializable;

public record TestFailure(String testClass, String testMethod, String message, String stacktrace)
        implements Serializable {
    
    private static final long serialVersionUID = 349718598568296233L;
    
    @Override
    public String toString() {
        return testClass + "::" + testMethod;
    }

}
