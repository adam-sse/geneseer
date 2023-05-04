package net.ssehub.program_repair.geneseer.evaluation;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageVisitor;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;

import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class JunitEvaluation {

    private static final Logger LOG = Logger.getLogger(JunitEvaluation.class.getName());
    
    private static final Path JACOCOAGENT;
    
    private static final Path GENESEER_TEST_DRIVER;
    
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
            
            JACOCOAGENT = tempDir.resolve("jacocoagent.jar");
            GENESEER_TEST_DRIVER = tempDir.resolve("geneseer-test-driver.jar");
            
            Files.write(JACOCOAGENT, JunitEvaluation.class.getClassLoader()
                    .getResourceAsStream("net/ssehub/program_repair/geneseer/evaluation/org.jacoco.agent.jar").readAllBytes());
            Files.write(GENESEER_TEST_DRIVER, JunitEvaluation.class.getClassLoader()
                    .getResourceAsStream("net/ssehub/program_repair/geneseer/evaluation/geneseer-test-driver.jar").readAllBytes());
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create temporary directory with evaluation jars", e);
            throw new UncheckedIOException(e);
        }
        
    }
    
    @SuppressWarnings("unchecked")
    public EvaluationResult runTests(Path workingDirectory, List<Path> classpath, Path classes, List<String> testClasses)
            throws EvaluationException {
        
        try (Probe probe = Measurement.INSTANCE.start("junit-evaluation")) {
            
            List<String> command = createCommand(classpath, testClasses);
            LOG.log(Level.FINE, () -> "Runnning " + command);
            ProcessRunner process;
            try {
                process = new ProcessRunner.Builder(command)
                        .workingDirectory(workingDirectory)
                        .timeout(TimeUnit.MINUTES.toMillis(5)) // TODO: make this configurable
                        .run();
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to start process for running tests", e);
                throw new EvaluationException("Failed to start process for running tests", e);
            }
            
            List<TestFailure> failures;
            try {
                ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(process.getStdout()));
                
                String stdout = (String) in.readObject();
                String stderr = (String) in.readObject();
                
                logProcessResult(stdout, stderr, process.getExitCode());
                
                failures = (List<TestFailure>) in.readObject();
                LOG.info(() -> failures.size() + " test failures");
                for (TestFailure failure : failures) {
                    LOG.fine(() -> "Failure: " + failure.toString() + " " + failure.message() /*+ "\n" + failure.stacktrace()*/);
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
            
            EvaluationResult result = new EvaluationResult();
            result.setFailures(failures);
            
            parseCoverageOutput(workingDirectory, classes, result);
            
            return result;
        }
    }

    private void parseCoverageOutput(Path workingDirectory, Path classDirectory, EvaluationResult evaluationResult)
            throws EvaluationException {
        
        Path jacocoExec = workingDirectory.resolve("jacoco.exec");
        
        try (InputStream jacocoExecStream = Files.newInputStream(jacocoExec)) {
            ExecutionDataStore executionDataStore = new ExecutionDataStore();
            SessionInfoStore sessionInfoStore = new SessionInfoStore();
            
            ExecutionDataReader executionDataReader = new ExecutionDataReader(jacocoExecStream);
            executionDataReader.setExecutionDataVisitor(executionDataStore);
            executionDataReader.setSessionInfoVisitor(sessionInfoStore);
            executionDataReader.read();
            
            Analyzer analyzer = new Analyzer(executionDataStore, new ICoverageVisitor() {
                
                @Override
                public void visitCoverage(IClassCoverage classCoverage) {
                    Set<Integer> coveredLines = new HashSet<>(classCoverage.getLastLine() - classCoverage.getLastLine());
                    Set<Integer> partiallyCoveredLines = new HashSet<>(classCoverage.getLastLine() - classCoverage.getLastLine());
                    
                    for (int i = classCoverage.getFirstLine(); i <= classCoverage.getLastLine(); i++) {
                        int status = classCoverage.getLine(i).getStatus();
                        switch (status) {
                        case ICounter.FULLY_COVERED:
                            coveredLines.add(i);
                            break;
                        case ICounter.PARTLY_COVERED:
                            partiallyCoveredLines.add(i);
                            break;
                            
                        default:
                            // ignore
                            break;
                        }
                    }
                    
                    evaluationResult.addClassCoverage(new ClassCoverage(
                            classCoverage.getName(), coveredLines, partiallyCoveredLines));
                }
                
            });
            analyzer.analyzeAll(classDirectory.toFile());
            
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

    private static void logProcessResult(String stdout, String stderr, int exitCode) {
        Level level = Level.FINE;
        if (exitCode != 0) {
            level = Level.WARNING;
        }
        
        LOG.log(level, () -> "Evaluation process finished with exit code " + exitCode);
        if (!stdout.isEmpty()) {
            LOG.log(level, () -> "stdout:\n" + stdout);
        } else {
            LOG.log(level, "stdout: <none>");
        }
        if (!stderr.isEmpty()) {
            LOG.log(level, () -> "stderr:\n" + stderr);
        } else {
            LOG.log(level, "stderr: <none>");
        }
    }

    private List<String> createCommand(List<Path> classpath, List<String> testClasses) {
        List<String> command = new LinkedList<>();
        command.add("java");
        command.add("-javaagent:" + JACOCOAGENT.toAbsolutePath());
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(GENESEER_TEST_DRIVER.toAbsolutePath().toString() + File.pathSeparatorChar
                + classpath.stream()
                    .map(Path::toString)
                    .reduce((p1, p2) -> p1 + File.pathSeparatorChar + p2)
                    .get());
        command.add("net.ssehub.program_repair.geneseer.evaluation.JunitRunnerClient"); // TODO?
        command.addAll(testClasses);
        return command;
    }
    
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws EvaluationException {
        List<TestFailure> failures;
        try {
            ObjectInputStream in = new ObjectInputStream(new FileInputStream(new File("out.bin")));
            
            String stdout = (String) in.readObject();
            String stderr = (String) in.readObject();
            
            logProcessResult(stdout, stderr, 1);
            
            failures = (List<TestFailure>) in.readObject();
            LOG.info(() -> "Failures: " + failures);
            
            
        } catch (IOException | ClassNotFoundException e) {
            LOG.log(Level.SEVERE, "Failed to read process output", e);
            
            throw new EvaluationException("Failed to read process output", e);
        }
    }

}
