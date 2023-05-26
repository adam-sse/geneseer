package net.ssehub.program_repair.geneseer.evaluation;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public abstract class AbstractTestExecution {

    public static final String CLASS_LIST_RUNNER = "net.ssehub.program_repair.geneseer.evaluation.ClassListRunner";
    
    public static final String SINGLE_METHOD_RUNNER = "net.ssehub.program_repair.geneseer.evaluation.SingleTestMethodRunner";
    
    private static final Logger LOG = Logger.getLogger(AbstractTestExecution.class.getName());
    
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
    
    protected abstract List<Path> getClasspath();
    
    protected abstract String getMainClass();
    
    protected abstract List<String> getArguments();
    
    protected abstract boolean withJacoco();
    
    @SuppressWarnings("unchecked")
    protected List<TestResult> executeTests(Path workingDirectory) throws EvaluationException {
        ProcessRunner process;
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            List<String> command = createCommand(tempDirManager.createTemporaryDirectory());
            LOG.log(Level.FINE, () -> "Runnning " + command);
            
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
            if (executedTests == null) {
                throw new IOException("read null as list of executed tests");
            }
            
            LOG.info(() -> executedTests.size() + " tests executed, "
                        + executedTests.stream().filter(TestResult::isFailure).count() + " failures");
            for (TestResult test : executedTests) {
                if (test.isFailure()) {
                    LOG.fine(() -> "Failure: " + test.toString() + " " + test.failureMessage());
                }
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

        return executedTests;
    }
    
    private static void logProcessResult(String stdout, String stderr, int exitCode) {
        Level level = Level.FINE;
        if (exitCode != 0) {
            level = Level.WARNING;
        }

        LOG.log(level, () -> "Evaluation process finished with exit code " + exitCode);
        if (stdout != null && !stdout.isEmpty()) {
            LOG.log(level, () -> "stdout:\n" + stdout);
        }
        if (stderr != null && !stderr.isEmpty()) {
            LOG.log(level, () -> "stderr:\n" + stderr);
        }
    }
    
    private List<String> createCommand(Path tempDir) throws IOException {
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.getJvmBinaryPath());
        
        if (withJacoco()) {
            command.add("-javaagent:" + JACOCO_AGENT.toAbsolutePath());
        }
        
        command.add("-Dfile.encoding=" + Configuration.INSTANCE.getEncoding());
        command.add("-Djava.io.tmpdir=" + tempDir);

        StringBuilder cp = new StringBuilder(GENESEER_TEST_DRIVER.toAbsolutePath().toString());
        cp.append(File.pathSeparatorChar);
        for (Path element : getClasspath()) {
            cp.append(File.pathSeparatorChar);
            cp.append(element.toString());
        }

        command.add("-cp");
        command.add(cp.toString());

        command.add(getMainClass());
        command.addAll(getArguments());
        return command;
    }
    
}
