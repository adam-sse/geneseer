package eu.stamp_project.testrunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;

import eu.stamp_project.testrunner.listener.CoveredTestResultPerTestMethod;
import eu.stamp_project.testrunner.listener.CoveredTestResultPerTestMethodImpl;
import eu.stamp_project.testrunner.listener.impl.CoverageDetailed;
import eu.stamp_project.testrunner.listener.impl.CoverageFromClass;
import eu.stamp_project.testrunner.listener.impl.CoverageInformation;
import eu.stamp_project.testrunner.runner.ParserOptions;
import net.ssehub.program_repair.geneseer.evaluation.AbstractTestExecution;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class EntryPoint extends AbstractTestExecution {
    
    public static final EntryPoint INSTANCE = new EntryPoint();
    
    private static final Logger LOG = Logger.getLogger(JunitEvaluation.class.getName());
    
    private Path workingDirectoryInternal;
    
    private List<Path> classpath;
    
    private Path classesDirectory;
    
    private String testClass;
    
    private String testMethod;
    
    public void setup(Path workingDirectory, List<Path> classpath, Path classesDirectory) {
        this.workingDirectoryInternal = workingDirectory;
        
        this.classpath = new ArrayList<>(classpath.size() + 1);
        this.classpath.add(classesDirectory);
        this.classpath.addAll(classpath);
        
        this.classesDirectory = classesDirectory;
    }
    
    @Override
    protected List<Path> getClasspath() {
        return classpath;
    }
    
    @Override
    protected String getMainClass() {
        return SINGLE_METHOD_RUNNER;
    }
    
    @Override
    protected List<String> getArguments() {
        return List.of(testClass, testMethod);
    }
    
    @Override
    protected boolean withJacoco() {
        return true;
    }
    
    public CoveredTestResultPerTestMethod run(String... methodNames) throws EvaluationException {
        LOG.info(() -> "Running coverage on " + methodNames.length + " test methods");
        
        CoveredTestResultPerTestMethodImpl coverageResult = new CoveredTestResultPerTestMethodImpl();
        
        try (Probe probe = Measurement.INSTANCE.start("junit-coverage-matrix")) {
            
            for (String methodName : methodNames) {
                String className = methodName.split("#")[0];
                methodName = methodName.split("#")[1];
                
                runCoverage(className, methodName, coverageResult);
            }
        }
        
        return coverageResult;
    }
    
    private void runCoverage(String testClass, String testMethod, CoveredTestResultPerTestMethodImpl coverage)
            throws EvaluationException {
        
        TestResult result = runSingleTestMethod(testClass, testMethod);
        
        if (result != null) {
            if (result.isFailure()) {
                coverage.addFailingTest(result);
                LOG.fine(() -> "Failure: " + result + " " + result.failureMessage());
            } else {
                coverage.addPassingTest(result);
                LOG.fine(() -> "Passed: " + result);
            }
            
            Path jacocoExec = workingDirectoryInternal.resolve("jacoco.exec");
            CoverageInformation coverageInformation = parseCoverage(jacocoExec);
            
            coverage.getCoverageResultsMap().put(testClass + "#" + testMethod, new CoverageDetailed(coverageInformation));
            
        } else {
            LOG.fine("Test seems to be ignored");
            coverage.addIgnored(testClass + "#" + testMethod);
        }
    }
    
    private CoverageInformation parseCoverage(Path jacocoExec) throws EvaluationException {
        try (InputStream jacocoExecStream = Files.newInputStream(jacocoExec)) {
            ExecutionDataStore executionDataStore = new ExecutionDataStore();
            SessionInfoStore sessionInfoStore = new SessionInfoStore();

            ExecutionDataReader executionDataReader = new ExecutionDataReader(jacocoExecStream);
            executionDataReader.setExecutionDataVisitor(executionDataStore);
            executionDataReader.setSessionInfoVisitor(sessionInfoStore);
            executionDataReader.read();

            return transformJacocoObject(executionDataStore);

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to analyze coverage", e);
            throw new EvaluationException("Failed to analyze coverage", e);

        } finally {
            if (Files.isRegularFile(jacocoExec)) {
                try {
                    Files.delete(jacocoExec);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to delete jacoco.exec", e);
                }
            }
        }
    }
    
    private CoverageInformation transformJacocoObject(ExecutionDataStore executionData)
            throws IOException {

        CoverageInformation covered = new CoverageInformation();
        
        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
        analyzer.analyzeAll(classesDirectory.toFile());

        for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {

            Map<Integer, Integer> covClass = new HashMap<>();

            for (IMethodCoverage methodCoverage : classCoverage.getMethods()) {

                if (!"<clinit>".equals(methodCoverage.getName())) {

                    for (int i = methodCoverage.getFirstLine(); i <= methodCoverage.getLastLine() + 1; i++) {
                        int coveredI = methodCoverage.getLine(i).getInstructionCounter().getCoveredCount();
                        covClass.put(i, coveredI);
                    }

                }
            }
            CoverageFromClass l = new CoverageFromClass(classCoverage.getName(), classCoverage.getPackageName(),
                    classCoverage.getFirstLine(), classCoverage.getLastLine(), covClass);

            covered.put(classCoverage.getName(), l);

        }
        return covered;
    }
    
    
    private TestResult runSingleTestMethod(String testClass, String testMethod) throws EvaluationException {
        this.testClass = testClass;
        this.testMethod = testMethod;
        List<TestResult> executedTests = executeTests(workingDirectoryInternal);
        return executedTests.size() == 1 ? executedTests.get(0) : null;
    }
    
    /*
     * Compatibility with original test-runner class as used by flacoco 
     */
    
    public static boolean useOptionsFile;
    
    public static File workingDirectory;
    
    public static boolean verbose;
    
    public static int timeoutInMs;
    
    public static String JVMArgs;
    
    public static boolean jUnit5Mode; 
    
    public static String jacocoAgentIncludes;
    
    public static ParserOptions.CoverageTransformerDetail coverageDetail;
    
    public static CoveredTestResultPerTestMethod runOnlineCoveredTestResultPerTestMethods(
            String classpath, List<String> targetSourceClasses, List<String> targetTestClasses,
            String[] fullQualifiedNameOfTestClasses, String[] methodNames) {
        
        try {
            return INSTANCE.run(methodNames);
        } catch (EvaluationException e) {
            throw new RuntimeException(e);
        }
    }
    
}
