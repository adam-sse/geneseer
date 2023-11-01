package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.util.TimeUtils;

public class Configuration {

    public static final Configuration INSTANCE = new Configuration();
    
    private static final Logger LOG = Logger.getLogger(Configuration.class.getName());
    
    private String jvmBinaryPath = "java";
    
    private String javaCompilerBinaryPath = "javac";
    
    private int testExecutionTimeoutMs = (int) TimeUnit.MINUTES.toMillis(2);
    
    private long randomSeed = 0;
    
    private boolean coverageMatrixSimplified = true;
    
    public enum TestsToRun {
        ALL_TESTS, RELEVANT_TESTS
    }

    private TestsToRun testsToRun = TestsToRun.ALL_TESTS;
    
    private boolean debugTestDriver = false;
    
    public void loadFromFile(Path file) throws IOException {
        LOG.info(() -> "Loading configuration file " + file.toAbsolutePath());
        Properties properties = new Properties();
        properties.load(Files.newBufferedReader(file));
        
        for (Object key : properties.keySet()) {
            String value = properties.getProperty((String) key);
            switch ((String) key) {
            case "jvmBinaryPath":
                jvmBinaryPath = value;
                break;
            case "javaCompilerBinaryPath":
                javaCompilerBinaryPath = value;
                break;
            case "testExecutionTimeoutMs":
                testExecutionTimeoutMs = Integer.parseInt(value);
                break;
            case "randomSeed":
                randomSeed = Long.parseLong(value);
                break;
            case "coverageMatrixSimplified":
                coverageMatrixSimplified = Boolean.parseBoolean(value);
                break;
            case "testsToRun":
                testsToRun = TestsToRun.valueOf(value);
                break;
            case "debugTestDriver":
                debugTestDriver = Boolean.parseBoolean(value);
                break;
            
            default:
                LOG.warning(() -> "Unknown configuration key " + key);
                break;
            }
        }
    }
    
    public void log() {
        System.out.println(testExecutionTimeoutMs);
        LOG.config("Configuration:");
        LOG.config(() -> "    JVM binary path: " + jvmBinaryPath);
        LOG.config(() -> "    Java compiler binary path: " + javaCompilerBinaryPath);
        LOG.config(() -> "    Test execution timeout: " + TimeUtils.formatMilliseconds(testExecutionTimeoutMs));
        LOG.config(() -> "    Random seed: " + randomSeed);
        LOG.config(() -> "    Simplified coverage matrix: " + coverageMatrixSimplified);
        LOG.config(() -> "    Tests to run: " + testsToRun);
        LOG.config(() -> "    Debug test driver: " + debugTestDriver);
    }
    
    public String getJvmBinaryPath() {
        return  jvmBinaryPath;
    }
    
    public String getJavaCompilerBinaryPath() {
        return javaCompilerBinaryPath;
    }
    
    public int getTestExecutionTimeoutMs() {
        return testExecutionTimeoutMs;
    }
    
    public long getRandomSeed() {
        return randomSeed;
    }
    
    public boolean getCoverageMatrixSimplified() {
        return coverageMatrixSimplified;
    }
    
    public TestsToRun getTestsToRun() {
        return testsToRun;
    }
    
    public boolean getDebugTestDriver() {
        return debugTestDriver;
    }
    
}
