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
    
    private boolean coverageMatrixSimplified = true;
    
    public enum TestsToRun {
        ALL_TESTS, RELEVANT_TESTS
    }

    private TestsToRun testsToRun = TestsToRun.ALL_TESTS;
    
    private boolean debugTestDriver = false;
    
    private long randomSeed = 0;
    
    private int populationSize = 40;
    
    private int generationLimit = 10;
    
    private double negativeTestsWeight = 10;
    
    private double positiveTestsWeight = 1;
    
    private double mutationProbability = 4;
    
    public enum MutationScope {
        GLOBAL, FILE
    }
    
    private MutationScope statementScope = MutationScope.GLOBAL;
    
    public void loadFromFile(Path file) throws IOException {
        LOG.info(() -> "Loading configuration file " + file.toAbsolutePath());
        Properties properties = new Properties();
        properties.load(Files.newBufferedReader(file));
        
        for (Object key : properties.keySet()) {
            String value = properties.getProperty((String) key);
            switch ((String) key) {
            case "setup.jvmBinaryPath":
                jvmBinaryPath = value;
                break;
            case "setup.javaCompilerBinaryPath":
                javaCompilerBinaryPath = value;
                break;
            case "setup.testExecutionTimeoutMs":
                testExecutionTimeoutMs = Integer.parseInt(value);
                break;
            case "setup.coverageMatrixSimplified":
                coverageMatrixSimplified = Boolean.parseBoolean(value);
                break;
            case "setup.testsToRun":
                testsToRun = TestsToRun.valueOf(value.toUpperCase());
                break;
            case "setup.debugTestDriver":
                debugTestDriver = Boolean.parseBoolean(value);
                break;
            case "genetic.randomSeed":
                randomSeed = Long.parseLong(value);
                break;
            case "genetic.populationSize":
                populationSize = Integer.parseInt(value);
                break;
            case "genetic.generationLimit":
                generationLimit = Integer.parseInt(value);
                break;
            case "genetic.negativeTestsWeight":
                negativeTestsWeight = Double.parseDouble(value);
                break;
            case "genetic.positiveTestsWeight":
                positiveTestsWeight = Double.parseDouble(value);
                break;
            case "genetic.mutationProbability":
                mutationProbability = Double.parseDouble(value);
                break;
            case "genetic.statementScope":
                statementScope = MutationScope.valueOf(value.toUpperCase());
                break;
            
            default:
                LOG.warning(() -> "Unknown configuration key " + key);
                break;
            }
        }
    }
    
    public void log() {
        LOG.config("Setup Configuration:");
        LOG.config(() -> "    JVM binary path: " + jvmBinaryPath);
        LOG.config(() -> "    Java compiler binary path: " + javaCompilerBinaryPath);
        LOG.config(() -> "    Test execution timeout: " + TimeUtils.formatMilliseconds(testExecutionTimeoutMs));
        LOG.config(() -> "    Simplified coverage matrix: " + coverageMatrixSimplified);
        LOG.config(() -> "    Tests to run: " + testsToRun);
        LOG.config(() -> "    Debug test driver: " + debugTestDriver);
        LOG.config("Genetic Configuration:");
        LOG.config(() -> "    Random seed: " + randomSeed);
        LOG.config(() -> "    Population size: " + populationSize);
        LOG.config(() -> "    Generation limit: " + generationLimit);
        LOG.config(() -> "    Negative tests weight: " + negativeTestsWeight);
        LOG.config(() -> "    Positive tests weight: " + positiveTestsWeight);
        LOG.config(() -> "    Mutation probability: " + mutationProbability);
        LOG.config(() -> "    Statement Scope: " + statementScope);
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
    
    public boolean getCoverageMatrixSimplified() {
        return coverageMatrixSimplified;
    }
    
    public TestsToRun getTestsToRun() {
        return testsToRun;
    }
    
    public boolean getDebugTestDriver() {
        return debugTestDriver;
    }

    public long getRandomSeed() {
        return randomSeed;
    }
    
    public int getPopulationSize() {
        return populationSize;
    }
    
    public int getGenerationLimit() {
        return generationLimit;
    }
    
    public double getNegativeTestsWeight() {
        return negativeTestsWeight;
    }
    
    public double getPositiveTestsWeight() {
        return positiveTestsWeight;
    }
    
    public double getMutationProbability() {
        return mutationProbability;
    }
    
    
    public MutationScope getStatementScope() {
        return statementScope;
    }
    
}
