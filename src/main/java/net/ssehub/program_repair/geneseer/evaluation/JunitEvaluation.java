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
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class JunitEvaluation {

    private static final Logger LOG = Logger.getLogger(JunitEvaluation.class.getName());
    
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

            GENESEER_TEST_DRIVER = tempDir.resolve("geneseer-test-driver.jar");

            Files.write(GENESEER_TEST_DRIVER, JunitEvaluation.class.getClassLoader()
                    .getResourceAsStream("net/ssehub/program_repair/geneseer/evaluation/geneseer-test-driver.jar")
                    .readAllBytes());

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create temporary directory with evaluation jars", e);
            throw new UncheckedIOException(e);
        }
    }
    
    @SuppressWarnings("unchecked")
    public EvaluationResult runTests(Path workingDirectory, List<Path> classpath, Path classes, List<String> testClasses)
            throws EvaluationException {
        
        try (Probe probe = Measurement.INSTANCE.start("junit-evaluation")) {
            
            List<String> command = createCommand(classpath, classes, testClasses);
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

            EvaluationResult result = new EvaluationResult();
            result.setExecutedTests(executedTests);

            return result;
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

    private List<String> createCommand(List<Path> classpath, Path classes, List<String> testClasses) {
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.getJvmBinaryPath());
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

        command.add("net.ssehub.program_repair.geneseer.evaluation.JunitRunnerClient");
        command.addAll(testClasses);
        return command;
    }

}
