package net.ssehub.program_repair.geneseer.evaluation;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class TestExecution implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TestExecution.class.getName());
    
    private static final int JACOCO_PORT = 6300;
    
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
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Process process;
    
    private ObjectInputStream in;
    
    private ObjectOutputStream out;
    
    public TestExecution(Path workingDirectory, List<Path> classpath, boolean withCoverage) throws EvaluationException {
        tempDirManager = new TemporaryDirectoryManager();
        
        try {
            startProcess(workingDirectory, classpath, withCoverage);
        } catch (IOException e) {
            throw new EvaluationException("Failed to start runner process", e);
        }
    }
    
    private void startProcess(Path workingDirectory, List<Path> classpath, boolean withCoverage) throws IOException {
        List<String> command = createCommand(classpath, withCoverage);
        LOG.fine(() -> "Running test runner proces: " + command);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectError(Redirect.INHERIT); // TODO: for now, we just hope there is no error output
        
        process = builder.start();
        out = new ObjectOutputStream(process.getOutputStream());
        in = new ObjectInputStream(process.getInputStream());
    }
    
    @Override
    public void close() throws EvaluationException {
        LOG.fine("Stopping test execution process"); 
        
        process.destroy();
        boolean terminated = ProcessRunner.untilNoInterruptedException(
                () -> process.waitFor(500, TimeUnit.MILLISECONDS));
        if (!terminated) {
            process.destroyForcibly();
        }
        
        try {
            tempDirManager.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close temporary directory manager", e);
        }
    }

    public List<TestResult> executeTestClass(String className) throws EvaluationException {
        try {
            out.writeObject("CLASS");
            out.writeObject(className);
            out.flush();
            
            @SuppressWarnings("unchecked")
            List<TestResult> result = (List<TestResult>) in.readObject();
            
            LOG.fine(() -> result.size() + " tests run in test class " + className + ", "
                    + result.stream().filter(TestResult::isFailure).count() + " failures");
            
            return result;
            
        } catch (IOException | ClassNotFoundException e) {
            throw new EvaluationException("Communication with runner process failed", e);
        }
    }
    
    public class TestResultWithCoverage {
        
        private TestResult testResult;
        
        private ExecutionDataStore coverage;
        
        public TestResultWithCoverage(TestResult testResult, ExecutionDataStore coverage) {
            this.testResult = testResult;
            this.coverage = coverage;
        }
        
        public TestResult getTestResult() {
            return testResult;
        }
        
        public ExecutionDataStore getCoverage() {
            return coverage;
        }
    }
    
    public TestResultWithCoverage executeTestMethodWithCoverage(String className, String methodName)
            throws EvaluationException {
        
        try {
            ExecDumpClient jacocoClient = new ExecDumpClient();
            jacocoClient.setReset(true);
            jacocoClient.setDump(false);
            jacocoClient.dump("localhost", JACOCO_PORT);
            
            out.writeObject("METHOD");
            out.writeObject(className);
            out.writeObject(methodName);
            out.flush();
            
            TestResult result = (TestResult) in.readObject();
            
            jacocoClient.setDump(true);
            ExecFileLoader loader = jacocoClient.dump("localhost", JACOCO_PORT);
            
            return new TestResultWithCoverage(result, loader.getExecutionDataStore());
            
        } catch (IOException | ClassNotFoundException e) {
            throw new EvaluationException("Communication with runner process failed", e);
        }
    }
    
    private List<String> createCommand(List<Path> classpath, boolean withCoverage)
            throws IOException {
        
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.getJvmBinaryPath());
        
        if (withCoverage) {
            command.add("-javaagent:" + JACOCO_AGENT.toAbsolutePath() + "=output=tcpserver,port=" + JACOCO_PORT);
        }
        
        command.add("-Dfile.encoding=" + Configuration.INSTANCE.getEncoding());
        command.add("-Djava.io.tmpdir=" + tempDirManager.createTemporaryDirectory());

        StringBuilder cp = new StringBuilder(GENESEER_TEST_DRIVER.toAbsolutePath().toString());
        cp.append(File.pathSeparatorChar);
        for (Path element : classpath) {
            cp.append(File.pathSeparatorChar);
            cp.append(element.toString());
        }

        command.add("-cp");
        command.add(cp.toString());

        command.add("net.ssehub.program_repair.geneseer.evaluation.Runner");
        return command;
    }
    
}
