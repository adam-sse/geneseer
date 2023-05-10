package eu.stamp_project.testrunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
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
import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class EntryPoint {
    
    public static final EntryPoint INSTANCE = new EntryPoint();
    
    private static final Logger LOG = Logger.getLogger(JunitEvaluation.class.getName());
    
    private static final Path GENESEER_TEST_DRIVER;
    
    private static final Path JACOCO_AGENT;

    static {
        try {
            TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    tempDirManager.close();
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Failed to delete temporary directory", e);
                }
            }));

            Path tempDir = tempDirManager.createTemporaryDirectory();

            GENESEER_TEST_DRIVER = tempDir.resolve("geneseer-test-driver.jar");
            JACOCO_AGENT = tempDir.resolve("jacocoagent.jar");

            Files.write(GENESEER_TEST_DRIVER, JunitEvaluation.class.getClassLoader()
                    .getResourceAsStream("net/ssehub/program_repair/geneseer/evaluation/geneseer-test-driver.jar")
                    .readAllBytes());
            
            Files.write(JACOCO_AGENT, JunitEvaluation.class.getClassLoader()
                    .getResourceAsStream("net/ssehub/program_repair/geneseer/evaluation/org.jacoco.agent.jar").readAllBytes());

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create temporary directory with evaluation jars", e);
            throw new UncheckedIOException(e);
        }
    }
    
    private Path workingDirectoryInternal;
    
    private List<Path> classpath;
    
    private Path classes;
    
    public void setup(Path workingDirectory, List<Path> classpath, Path classes) {
        this.workingDirectoryInternal = workingDirectory;
        this.classpath = classpath;
        this.classes = classes;
    }
    
    public CoveredTestResultPerTestMethod run(String... methodNames) throws EvaluationException {
        LOG.info(() -> "Running coverage on " + methodNames.length + " test methods");
        
        CoveredTestResultPerTestMethodImpl coverageResult = new CoveredTestResultPerTestMethodImpl();
        
        try (Probe probe = Measurement.INSTANCE.start("junit-coverage-matrix")) {
            
            for (String methodName : methodNames) {
                String className = methodName.split("#")[0];
                methodName = methodName.split("#")[1];
                
                runCoverage(workingDirectoryInternal, classpath, classes, className, methodName, coverageResult);
            }
        }
        
        return coverageResult;
    }
    
    private void runCoverage(Path workingDirectory, List<Path> classpath, Path classes,
            String testClass, String testMethod, CoveredTestResultPerTestMethodImpl coverage) throws EvaluationException {
        
        TestResult result = runSingleTestMethod(workingDirectory, classpath, classes, testClass, testMethod);
        
        if (result != null) {
            if (result.isFailure()) {
                coverage.addFailingTest(result);
                LOG.fine(() -> "Failure: " + result + " " + result.failureMessage());
            } else {
                coverage.addPassingTest(result);
                LOG.fine(() -> "Passed: " + result);
            }
            
            Path jacocoExec = workingDirectory.resolve("jacoco.exec");
            CoverageInformation coverageInformation = parseCoverage(jacocoExec, classes);
            
            coverage.getCoverageResultsMap().put(testClass + "#" + testMethod, new CoverageDetailed(coverageInformation));
            
        } else {
            LOG.fine("Test seems to be ignored");
            coverage.addIgnored(testClass + "#" + testMethod);
        }
    }
    
    private CoverageInformation parseCoverage(Path jacocoExec, Path classesDirectory) throws EvaluationException {
        try (InputStream jacocoExecStream = Files.newInputStream(jacocoExec)) {
            ExecutionDataStore executionDataStore = new ExecutionDataStore();
            SessionInfoStore sessionInfoStore = new SessionInfoStore();

            ExecutionDataReader executionDataReader = new ExecutionDataReader(jacocoExecStream);
            executionDataReader.setExecutionDataVisitor(executionDataStore);
            executionDataReader.setSessionInfoVisitor(sessionInfoStore);
            executionDataReader.read();

            return transformJacocoObject(executionDataStore, classesDirectory);

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
    
    private CoverageInformation transformJacocoObject(ExecutionDataStore executionData, Path classesDirectory)
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
    
    @SuppressWarnings("unchecked")
    private TestResult runSingleTestMethod(Path workingDirectory, List<Path> classpath, Path classes,
            String testClass, String testMethod) throws EvaluationException {
        
        List<String> command = createCommand(classpath, classes, testClass, testMethod);
        LOG.log(Level.FINE, () -> "Runnning " + command);
        ProcessRunner process;
        try {
            process = new ProcessRunner.Builder(command)
                    .workingDirectory(workingDirectory)
                    .timeout(Configuration.INSTANCE.getTestExecutionTimeoutMs())
                    .run();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to start process for running tests", e);
            throw new EvaluationException("Failed to start process for running tests", e);
        }

        List<TestResult> executedTests;
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(process.getStdout()));

            String stdout = (String) in.readObject();
            String stderr = (String) in.readObject();

            logProcessResult(stdout, stderr, process.getExitCode());

            executedTests = (List<TestResult>) in.readObject();
            if (executedTests.size() > 1) {
                throw new EvaluationException("More than one test executed");
            }

        } catch (IOException | ClassNotFoundException e) {
            LOG.log(Level.SEVERE, "Failed to read process output", e);

            String stdout = new String(process.getStdout());
            String stderr = new String(process.getStderr());
            if (!stdout.isEmpty()) {
                LOG.log(Level.INFO, () -> "stdout:\n" + stdout);
            }
            if (!stderr.isEmpty()) {
                LOG.log(Level.INFO, () -> "stderr:\n" + stderr);
            }

            throw new EvaluationException("Failed to read process output", e);
        }

        return executedTests.size() == 1 ? executedTests.get(0) : null;
    }
    
    private static void logProcessResult(String stdout, String stderr, int exitCode) {
        Level level = Level.FINE;
        if (exitCode != 0) {
            level = Level.WARNING;
        }

        LOG.log(level, () -> "Evaluation process finished with exit code " + exitCode);
        if (!stdout.isEmpty()) {
            LOG.log(level, () -> "stdout:\n" + stdout);
        }
        if (!stderr.isEmpty()) {
            LOG.log(level, () -> "stderr:\n" + stderr);
        }
    }

    private List<String> createCommand(List<Path> classpath, Path classes, String testClass, String testMethod) {
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.getJvmBinaryPath());
        command.add("-javaagent:" + JACOCO_AGENT.toAbsolutePath());
        command.add("-Dfile.encoding=" + Configuration.INSTANCE.getEncoding());

        StringBuilder cp = new StringBuilder(GENESEER_TEST_DRIVER.toAbsolutePath().toString());
        cp.append(File.pathSeparatorChar);
        cp.append(classes.toString());
        for (Path element : classpath) {
            cp.append(File.pathSeparatorChar);
            cp.append(element.toString());
        }

        command.add("-cp");
        command.add(cp.toString());

        command.add("net.ssehub.program_repair.geneseer.evaluation.SingleTestMethodRunner");
        command.add(testClass);
        command.add(testMethod);
        return command;
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
