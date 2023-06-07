package eu.stamp_project.testrunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.TestExecution;
import net.ssehub.program_repair.geneseer.evaluation.TestExecution.TestResultWithCoverage;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class EntryPoint {
    
    public static final EntryPoint INSTANCE = new EntryPoint();
    
    private static final Logger LOG = Logger.getLogger(JunitEvaluation.class.getName());
    
    private Path workingDirectoryInternal;
    
    private List<Path> classpath;
    
    private Path classesDirectory;
    
    public void setup(Path workingDirectory, List<Path> classpath, Path classesDirectory) {
        this.workingDirectoryInternal = workingDirectory;
        
        this.classpath = new ArrayList<>(classpath.size() + 1);
        this.classpath.add(classesDirectory);
        this.classpath.addAll(classpath);
        
        this.classesDirectory = classesDirectory;
    }
    
    public CoveredTestResultPerTestMethod run(String... methodNames) throws EvaluationException {
        LOG.info(() -> "Running coverage on " + methodNames.length + " test methods");
        
        CoveredTestResultPerTestMethodImpl coverage = new CoveredTestResultPerTestMethodImpl();
        
        try (Probe probe = Measurement.INSTANCE.start("junit-coverage-matrix");
                TestExecution testExec = new TestExecution(workingDirectoryInternal, classpath, true)) {
            
            for (String methodName : methodNames) {
                String className = methodName.split("#")[0];
                methodName = methodName.split("#")[1];
                
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
                    
                    coverage.getCoverageResultsMap().put(className + "#" + methodName, new CoverageDetailed(coverageInformation));
                    
                } else {
                    LOG.fine("Test seems to be ignored");
                    coverage.addIgnored(className + "#" + methodName);
                }
            }
            
        } catch (IOException e) {
            throw new EvaluationException("Failed to parse jacoco data", e);
        }
        
        return coverage;
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
