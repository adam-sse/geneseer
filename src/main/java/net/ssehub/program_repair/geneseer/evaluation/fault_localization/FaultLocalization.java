package net.ssehub.program_repair.geneseer.evaluation.fault_localization;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionDataStore;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.TestCoverageException;
import net.ssehub.program_repair.geneseer.evaluation.TestExecution;
import net.ssehub.program_repair.geneseer.evaluation.TestExecution.TestResultWithCoverage;
import net.ssehub.program_repair.geneseer.evaluation.TestExecutionException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestTimeoutException;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class FaultLocalization {
    
    private static final Logger LOG = Logger.getLogger(FaultLocalization.class.getName());

    private Path workingDirectory;
    
    private List<Path> classpath;
    
    private Charset encoding;
    
    public FaultLocalization(Path workingDirectory, List<Path> classpath, Charset encoding) {
        this.workingDirectory = workingDirectory;
        this.classpath = classpath;
        this.encoding = encoding;
    }
    
    public void measureAndAnnotateSuspiciousness(Node ast, Path variantBinDir, List<TestResult> tests)
            throws TestExecutionException {
        
        LOG.info("Measuring suspiciousness");
        LinkedHashMap<Location, Double> suspiciousness = measureSuspiciousness(tests, variantBinDir, ast);
        
        Map<String, Node> classes = getFileNodesByClassName(ast);
        
        AtomicInteger suspiciousStatementCount = new AtomicInteger();
        for (Map.Entry<Location, Double> entry : suspiciousness.entrySet()) {
            String className = entry.getKey().className();
            int dollarIndex = className.indexOf('$');
            if (dollarIndex != -1) {
                className = className.substring(0, dollarIndex);
            }
            
            int line = entry.getKey().line();
            
            Node classNode = classes.get(className);
            if (classNode != null) {
                List<Node> matchingStatements = classNode.stream()
                        .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
                        .filter(n -> n.hasLine(line))
                        .toList();
                
                if (matchingStatements.isEmpty()) {
                    String cn = className;
                    LOG.info(() -> "Found no statements for suspicious " + entry.getValue() + " at "
                            + cn + ":" + line);
                } else if (matchingStatements.size() > 1) {
                    String cn = className;
                    LOG.info(() -> "Found " + matchingStatements.size() + " statements for " + cn
                            + ":" + line + "; adding suspiciousness to all of them");
                }
                    
                String cn = className;
                matchingStatements.stream()
                        .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
                        .forEach(n -> {
                            Double previousSuspiciousness = (Double) n.getMetadata(Metadata.SUSPICIOUSNESS);
                            if (previousSuspiciousness == null || entry.getValue() > previousSuspiciousness) {
                                LOG.fine(() -> "Suspicious " + entry.getValue() + " at " + cn + ":" + line
                                        + " '" + n.getText() + "'");
                                n.setMetadata(Metadata.SUSPICIOUSNESS, entry.getValue());
                                if (previousSuspiciousness == null) {
                                    suspiciousStatementCount.incrementAndGet();
                                }
                            }
                        });
            } else {
                String cn = className;
                LOG.warning(() -> "Can't find class name " + cn);
            }
        }
        LOG.info(() -> suspiciousStatementCount.get() + " suspicious statements");
    }

    private Map<String, Node> getFileNodesByClassName(Node ast) {
        Map<String, Node> classes = new HashMap<>(ast.childCount());
        for (Node file : ast.childIterator()) {
            String className = file.getMetadata(Metadata.FILE_NAME).toString().replaceAll("[/\\\\]", ".");
            if (className.endsWith(".java")) {
                className = className.substring(0, className.length() - ".java".length());
            }
            classes.put(className, file);
        }
        return classes;
    }
    
    public LinkedHashMap<Location, Double> measureSuspiciousness(List<TestResult> tests, Path classesDirectory,
            Node ast) throws TestExecutionException {
        
        try (Probe probe = Measurement.INSTANCE.start("fault-localization")) {
            Map<Location, Set<TestResult>> coverage = measureCoverage(tests, classesDirectory);
            annotateClassBasedCoverageOnAstNodes(coverage, ast);
            
            Map<Location, Double> suspiciousness = new HashMap<>(coverage.size());
            for (Map.Entry<Location, Set<TestResult>> coverageEntry : coverage.entrySet()) {
                int nPassingNotExecuting = 0;
                int nFailingNotExecuting = 0;
                int nPassingExecuting = 0;
                int nFailingExecuting = 0;
                
                for (TestResult test : tests) {
                    boolean testCoversLine = coverageEntry.getValue().contains(test);
                    
                    if (testCoversLine) {
                        if (test.isFailure()) {
                            nFailingExecuting++;
                        } else {
                            nPassingExecuting++;
                        }
                    } else {
                        if (test.isFailure()) {
                            nFailingNotExecuting++;
                        } else {
                            nPassingNotExecuting++;
                        }
                    }
                }
                
                double susValue = ochiaiFormula(nPassingNotExecuting, nFailingNotExecuting,
                        nPassingExecuting, nFailingExecuting);
                if (susValue > 0) {
                    suspiciousness.put(coverageEntry.getKey(), susValue);
                }
            }
            
            LinkedHashMap<Location, Double> sortedSuspiciousness = new LinkedHashMap<>(suspiciousness.size());
            suspiciousness.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                    .forEach(e -> sortedSuspiciousness.put(e.getKey(), e.getValue()));
            
            return sortedSuspiciousness;
        }
    }
    
    private void annotateClassBasedCoverageOnAstNodes(Map<Location, Set<TestResult>> coverage, Node ast) {
        Map<String, Node> classes = getFileNodesByClassName(ast);
        for (Node clazz : classes.values()) {
            if (clazz.getMetadata(Metadata.COVERED_BY) == null) {
                clazz.setMetadata(Metadata.COVERED_BY, new HashSet<>());
            }
        }
        
        for (Map.Entry<Location, Set<TestResult>> entry : coverage.entrySet()) {
            String className = entry.getKey().className();
            int dollarIndex = className.indexOf('$');
            if (dollarIndex != -1) {
                className = className.substring(0, dollarIndex);
            }
            
            Node classNode = classes.get(className);
            if (classNode != null) {
                @SuppressWarnings("unchecked")
                Set<String> coveredBy = (Set<String>) classNode.getMetadata(Metadata.COVERED_BY);
                for (TestResult testResult : entry.getValue()) {
                    coveredBy.add(testResult.testClass());
                }
            }
        }
        
    }
    
    private Map<Location, Set<TestResult>> measureCoverage(List<TestResult> tests, Path classesDirectory)
            throws TestExecutionException {
        
        Map<Location, Set<TestResult>> coverage = new HashMap<>();
        
        List<Path> classpath = new ArrayList<>(this.classpath.size() + 1);
        classpath.add(classesDirectory);
        classpath.addAll(this.classpath);
        
        try (Probe probe = Measurement.INSTANCE.start("junit-coverage-matrix");
                TestExecution testExec = new TestExecution(workingDirectory, classpath, encoding, true)) {
            testExec.setTimeout(Configuration.INSTANCE.setup().testExecutionTimeoutMs());
         
            if (Configuration.INSTANCE.setup().coverageMatrixSimplified()) {
                measureCoverageWithClassAggregation(tests, classesDirectory, testExec, coverage);
            } else {
                LOG.info(() -> "Running coverage on " + tests.size() + " test methods individually");
                for (TestResult test : tests) {
                    measureCoverageForSingleTest(test, coverage, testExec, classesDirectory);
                }
            }
        }
        
        return coverage;
    }
    
    private void measureCoverageWithClassAggregation(List<TestResult> tests, Path classesDirectory,
            TestExecution testExec, Map<Location, Set<TestResult>> result) throws TestExecutionException {
        
        Map<String, List<TestResult>> testsByClass = new HashMap<>();
        for (TestResult test : tests) {
            testsByClass
                    .computeIfAbsent(test.testClass(), key -> new LinkedList<>())
                    .add(test);
        }
        
        LOG.info(() -> "Running coverage on " + tests.size() + " test methods (aggregated in "
                + testsByClass.size() + " classes)");
        
        for (Map.Entry<String, List<TestResult>> entry : testsByClass.entrySet()) {
            measureCoverageForWholeClass(entry.getKey(), entry.getValue(), classesDirectory, testExec, result);
        }
    }
    
    private void measureCoverageForWholeClass(String className, List<TestResult> tests, Path classesDirectory,
            TestExecution testExec, Map<Location, Set<TestResult>> result) throws TestExecutionException {
        
        boolean containsFailure = false;
        List<TestResultWithCoverage> coverageResults = null;
        try {
            coverageResults = testExec.executeTestClassWithCoverage(className);
            
            for (TestResultWithCoverage testResult : coverageResults) {
                if (testResult.getTestResult().isFailure()) {
                    containsFailure = true;
                }
            }
        } catch (TestTimeoutException e) {
            containsFailure = true;
        }
        
        if (!containsFailure) {
            
            if (coverageResults.size() != tests.size()) {
                int size = coverageResults.size();
                LOG.warning(() -> "Got different number of test methods in class " + className
                        + " when running with coverage (got " + size + ", expected " + tests.size() + ")");
            }
            
            if (!coverageResults.isEmpty()) {
                List<TestResult> actuallyReturnedTests = new ArrayList<>(tests.size());
                for (TestResultWithCoverage coverageResult : coverageResults) {
                    Optional<TestResult> expected = tests.stream()
                            .filter(t -> t.testClass().equals(coverageResult.getTestResult().testClass()))
                            .filter(t -> t.testMethod().equals(coverageResult.getTestResult().testMethod()))
                            .findAny();
                    if (expected.isPresent()) {
                        actuallyReturnedTests.add(expected.get());
                        if (expected.get().isFailure() != coverageResult.getTestResult().isFailure()) {
                            LOG.warning(() -> "Test result for " + coverageResult.getTestResult()
                                    + " differs when run with coverage");
                        }
                    } else {
                        LOG.warning(() -> "Test " + coverageResult.getTestResult() + " is new with coverage");
                    }
                }
                
                parseJacocoCoverage(actuallyReturnedTests, coverageResults.get(0).getCoverage(), classesDirectory,
                        result);
            }
            
        } else {
            LOG.fine(() -> "Test class " + className + " contains failing tests; running " + tests.size()
                    + " test methods individually");
            for (TestResult test : tests) {
                measureCoverageForSingleTest(test, result, testExec, classesDirectory);
            }
        }
    }
    
    private void measureCoverageForSingleTest(TestResult test, Map<Location, Set<TestResult>> coverage,
            TestExecution testExec, Path classesDirectory) throws TestExecutionException {
        try {
            TestResultWithCoverage testResult = testExec.executeTestMethodWithCoverage(
                    test.testClass(), test.testMethod());
            
            if (testResult.getTestResult() != null) {
                if (test.isFailure() != testResult.getTestResult().isFailure()) {
                    LOG.warning(() -> "Test result for " + test + " differs when run with coverage");
                }
                
                parseJacocoCoverage(List.of(test), testResult.getCoverage(), classesDirectory, coverage);
                
                // TODO: flacoco additionally looks at the stack trace since the line that threw an exception is not
                // detected by jacoco:
                // https://github.com/ASSERT-KTH/flacoco/blob/bd19ee3ded4f052a87e6894175fb266a89e71da6/
                // src/main/java/fr/spoonlabs/flacoco/core/coverage/CoverageMatrix.java#L82-L128
                
            } else {
                LOG.warning("Test " + test + " did not return a result");
            }
            
        } catch (TestTimeoutException e) {
            LOG.warning(() -> "Test " + test + " timed out when run with coverage");
        }
    }
    
    private void parseJacocoCoverage(List<TestResult> test, ExecutionDataStore executionData, Path classesDirectory,
            Map<Location, Set<TestResult>> coverage) throws TestCoverageException {

        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
        
        try {
            analyzer.analyzeAll(classesDirectory.toFile());
        } catch (IOException e) {
            throw new TestCoverageException("Failed to parse jacoco data", e);
        }

        boolean foundCoveredLine = false;
        for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {

            String className = classCoverage.getName().replace('/', '.');
            for (IMethodCoverage methodCoverage : classCoverage.getMethods()) {
                for (int line = methodCoverage.getFirstLine(); line <= methodCoverage.getLastLine() + 1; line++) {
                    int coveredI = methodCoverage.getLine(line).getInstructionCounter().getCoveredCount();
                    if (coveredI > 0) {
                        foundCoveredLine = true;
                        coverage.compute(new Location(className, line), (k, v) -> {
                            if (v == null) {
                                v = new HashSet<>();
                            }
                            v.addAll(test);
                            return v;
                        });
                    }
                }
            }
        }
        
        if (!foundCoveredLine) {
            LOG.warning(() -> "Found no coverage for tests " + test.stream().map(TestResult::toString).toList());
        }
    }
    
    // taken from flacoco
    private static double ochiaiFormula(int nPassingNotExecuting, int nFailingNotExecuting, int nPassingExecuting,
            int nFailingExecuting) {
        double result;
        if ((nFailingExecuting + nPassingExecuting == 0) || (nFailingExecuting + nFailingNotExecuting == 0)) {
            result = 0;
        } else {
            result = nFailingExecuting / Math.sqrt(
                    (nFailingExecuting + nFailingNotExecuting) * (nFailingExecuting + nPassingExecuting));
        }
        return result;
    }
    
}
