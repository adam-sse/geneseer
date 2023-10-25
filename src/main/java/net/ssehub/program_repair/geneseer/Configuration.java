package net.ssehub.program_repair.geneseer;

import java.util.concurrent.TimeUnit;

public class Configuration {

    public static final Configuration INSTANCE = new Configuration();
    
    public String getJvmBinaryPath() {
        return "java";
    }
    
    public String getJavaCompilerBinaryPath() {
        return "javac";
    }
    
    public int getTestExecutionTimeoutMs() {
        return (int) TimeUnit.MINUTES.toMillis(2);
    }
    
    public long getRandomSeed() {
        return 0;
    }
    
    public boolean getCoverageMatrixSimplified() {
        return false;
    }
    
    public enum TestsToRun {
        ALL_TESTS, RELEVANT_TESTS
    }
    public TestsToRun getTestsToRun() {
        return TestsToRun.RELEVANT_TESTS;
    }
    
    public boolean getDebugTestDriver() {
        return false;
    }
    
}
