package eu.stamp_project.testrunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionDataStore;

import eu.stamp_project.testrunner.listener.CoveredTestResultPerTestMethod;
import eu.stamp_project.testrunner.listener.CoveredTestResultPerTestMethodImpl;
import eu.stamp_project.testrunner.listener.impl.CoverageDetailed;
import eu.stamp_project.testrunner.listener.impl.CoverageFromClass;
import eu.stamp_project.testrunner.listener.impl.CoverageInformation;
import eu.stamp_project.testrunner.runner.ParserOptions;
import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.TestExecution;
import net.ssehub.program_repair.geneseer.evaluation.TestExecution.TestResultWithCoverage;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TimeoutException;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class EntryPoint {
    
    public static final EntryPoint INSTANCE = new EntryPoint();
    
    private static final Logger LOG = Logger.getLogger(JunitEvaluation.class.getName());
    
    private Path workingDirectoryInternal;
    
    private List<Path> classpath;
    
    private Path classesDirectory;
    
    private Charset encoding;
    
    public void setup(Path workingDirectory, List<Path> classpath, Path classesDirectory, Charset encoding) {
        this.workingDirectoryInternal = workingDirectory;
        
        this.classpath = new ArrayList<>(classpath.size() + 1);
        this.classpath.add(classesDirectory);
        this.classpath.addAll(classpath);
        
        this.classesDirectory = classesDirectory;
        this.encoding = encoding;
    }
    
    public CoveredTestResultPerTestMethod run(String... methodNames) throws EvaluationException {
        try (Probe probe = Measurement.INSTANCE.start("junit-coverage-matrix");
                TestExecution testExec = new TestExecution(workingDirectoryInternal, classpath, encoding, true)) {
            testExec.setTimeout(Configuration.INSTANCE.getTestExecutionTimeoutMs());
            
            CoveredTestResultPerTestMethod result;
            if (Configuration.INSTANCE.getCoverageMatrixSimplified()) {
                result = runWithClassAggregation(testExec, methodNames);
            } else {
                result = runAllMethodsIndividually(testExec, methodNames);
            }
            
            return result;
        }
    }
    
    private CoveredTestResultPerTestMethod runAllMethodsIndividually(TestExecution testExec, String... methodNames)
            throws EvaluationException {
        LOG.info(() -> "Running coverage on " + methodNames.length + " test methods individually");
        
        CoveredTestResultPerTestMethodImpl coverage = new CoveredTestResultPerTestMethodImpl();
        
        for (String methodName : methodNames) {
            String className = methodName.split("#")[0];
            methodName = methodName.split("#")[1];
            
            try {
                TestResultWithCoverage result = testExec.executeTestMethodWithCoverage(className, methodName);
                TestResult testResult = result.getTestResult();
                
                if (testResult != null) {
                    if (testResult.isFailure()) {
                        coverage.addFailingTest(testResult);
                        LOG.fine(() -> "Failure: " + testResult + " " + testResult.failureMessage());
                    } else {
                        coverage.addPassingTest(testResult);
                        LOG.fine(() -> "Passed: " + testResult);
                    }
                    
                    CoverageInformation coverageInformation = transformJacocoObject(result.getCoverage());
                    
                    coverage.getCoverageResultsMap().put(className + "#" + methodName,
                            new CoverageDetailed(coverageInformation));
                    
                } else {
                    LOG.fine("Test " + className + "::" + methodName + " seems to be ignored");
                    coverage.addIgnored(className + "#" + methodName);
                }
                
            } catch (TimeoutException e) {
                coverage.addFailingTest(new TestResult(className, methodName, "Timeout", "Timeout"));
            }
        }
        
        return coverage;
    }
    
    private CoveredTestResultPerTestMethod runWithClassAggregation(TestExecution testExec, String... methodNames)
            throws EvaluationException {
        CoveredTestResultPerTestMethodImpl coverage = new CoveredTestResultPerTestMethodImpl();
        
        Map<String, List<String>> methodsByClass = new HashMap<>();
        
        for (String methodName : methodNames) {
            String className = methodName.split("#")[0];
            methodName = methodName.split("#")[1];
            
            methodsByClass
                    .computeIfAbsent(className, key -> new LinkedList<>())
                    .add(methodName);
        }
        
        LOG.info(() -> {
            int methodCount = methodsByClass.values().stream()
                        .mapToInt(List::size)
                        .sum();
            return "Running coverage on " + methodCount + " test methods (aggregated in "
                    + methodsByClass.size() + " classes)";
        });
        
        for (Map.Entry<String, List<String>> entry : methodsByClass.entrySet()) {
            runOnClass(entry.getKey(), entry.getValue(), testExec, coverage);
        }
            
        return coverage;
    }
    
    private void runOnClass(String className, List<String> methodNames, TestExecution testExec,
            CoveredTestResultPerTestMethodImpl coverage) throws EvaluationException {

        boolean containsFailure = false;
        List<TestResultWithCoverage> results = null;
        try {
            results = testExec.executeTestClassWithCoverage(className);
            
            for (TestResultWithCoverage testResult : results) {
                if (testResult.getTestResult().isFailure()) {
                    containsFailure = true;
                }
            }
        } catch (TimeoutException e) {
            containsFailure = true;
        }
            
        if (!containsFailure) {
            
            CoverageDetailed coverageDetailed = null;
            if (results.size() > 0) {
                CoverageInformation coverageInformation = transformJacocoObject(results.get(0).getCoverage());
                coverageDetailed = new CoverageDetailed(coverageInformation);
            }
            
            int numPassed = 0;
            int numIgnored = 0;
            for (String methodName : methodNames) {
                boolean found = false;
                for (TestResultWithCoverage testResult : results) {
                    if (testResult.getTestResult().testMethod().equals(methodName)) {
                        coverage.addPassingTest(testResult.getTestResult());
                        coverage.getCoverageResultsMap().put(className + "#" + methodName, coverageDetailed);
                        numPassed++;
                        found = true;
                    }
                }
                
                if (!found) {
                    LOG.fine("Test " + className + "::" + methodName + " seems to be ignored");
                    coverage.addIgnored(className + "#" + methodName);
                    numIgnored++;
                }
            }
            
            int p = numPassed;
            int i = numIgnored;
            LOG.fine(() -> "Ran test class " + className + " as a whole; " + p + " passed test methods and "
                    + i + " ignored");
            
        } else {
            LOG.fine(() -> "Test class " + className + " contains failing tests; running " + methodNames.size()
                    + " test methods individually");
            
            runTestMethodsOneByOne(className, methodNames, testExec, coverage);
        }
    }

    private void runTestMethodsOneByOne(String className, List<String> methodNames, TestExecution testExec,
            CoveredTestResultPerTestMethodImpl coverage) throws EvaluationException {
        
        for (String methodName : methodNames) {
            try {
                TestResultWithCoverage result = testExec.executeTestMethodWithCoverage(className, methodName);
                TestResult testResult = result.getTestResult();
                
                if (testResult != null) {
                    if (testResult.isFailure()) {
                        coverage.addFailingTest(testResult);
                        LOG.fine(() -> "Failure: " + testResult + " " + testResult.failureMessage());
                    } else {
                        coverage.addPassingTest(testResult);
                        LOG.fine(() -> "Passed: " + testResult);
                    }
                    
                    CoverageInformation coverageInformation = transformJacocoObject(result.getCoverage());
                    
                    coverage.getCoverageResultsMap().put(className + "#" + methodName,
                            new CoverageDetailed(coverageInformation));
                    
                } else {
                    LOG.fine("Test " + className + "::" + methodName + " seems to be ignored");
                    coverage.addIgnored(className + "#" + methodName);
                }
            } catch (TimeoutException e) {
                coverage.addFailingTest(new TestResult(className, methodName, "Timeout", "Timeout"));
            }
        }
    }
    
    private CoverageInformation transformJacocoObject(ExecutionDataStore executionData)
            throws EvaluationException {

        CoverageInformation covered = new CoverageInformation();
        
        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
        
        try {
            analyzer.analyzeAll(classesDirectory.toFile());
        } catch (IOException e) {
            throw new EvaluationException("Failed to parse jacoco data", e);
        }

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
    
    /*
     * Compatibility with original test-runner class as used by flacoco 
     */
    
    
    // checkstyle: stop declaration order check
    // checkstyle: stop visibility modifier check
    // checkstyle: stop member name check
    
    public static boolean useOptionsFile;
    
    public static File workingDirectory;
    
    public static boolean verbose;
    
    public static int timeoutInMs;
    
    public static String JVMArgs;
    
    public static boolean jUnit5Mode; 
    
    public static String jacocoAgentIncludes;
    
    public static ParserOptions.CoverageTransformerDetail coverageDetail;
    
    // checkstyle: resume declaration order check
    // checkstyle: resume visibility modifier check
    // checkstyle: resume member name check
    
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
