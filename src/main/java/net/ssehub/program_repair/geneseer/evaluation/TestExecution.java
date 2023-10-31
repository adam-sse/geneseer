package net.ssehub.program_repair.geneseer.evaluation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.ServerSocket;
import java.nio.charset.Charset;
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
                    .getResourceAsStream("net/ssehub/program_repair/geneseer/evaluation/org.jacoco.agent.jar")
                    .readAllBytes());

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create temporary directory with evaluation jars", e);
            throw new UncheckedIOException(e);
        }
    }
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Path workingDirectory;
    
    private List<Path> classpath;
    
    private Charset encoding;
    
    private boolean withCoverage;
    
    private Process process;
    
    private ObjectInputStream in;
    
    private InputStream rawIn;
    
    private ObjectOutputStream out;
    
    private long timeoutMs = -1;
    
    private int jacocoPort;
    
    public TestExecution(Path workingDirectory, List<Path> classpath, Charset encoding, boolean withCoverage)
            throws EvaluationException {
        tempDirManager = new TemporaryDirectoryManager();
        this.workingDirectory = workingDirectory;
        this.classpath = classpath;
        this.encoding = encoding;
        this.withCoverage = withCoverage;
        
        startProcess();
    }
    
    public void setTimeout(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
    
    private int generateRandomPort() {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            port = socket.getLocalPort();
            
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to get free port; using random port number (might not be free)", e);
            port = (int) (Math.random() * (65535 - 49152)) + 49152;
        }
        
        return port;
    }
    
    private void startProcess() throws EvaluationException {
        try {
            if (withCoverage) {
                jacocoPort = generateRandomPort();
            }
            
            List<String> command = createCommand(classpath, withCoverage);
            LOG.fine(() -> {
                List<String> shortened = new LinkedList<>(command);
                shortened.set(shortened.indexOf("-cp") + 1, "<...>");
                return "Starting test driver process: " + shortened + " in " + workingDirectory;
            });
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.environment().put("TZ", "America/Los_Angeles");
            builder.redirectError(Redirect.INHERIT); // TODO: for now, we just hope there is no error output
            
            process = builder.start();
            out = new ObjectOutputStream(process.getOutputStream());
            rawIn = process.getInputStream();
            in = new ObjectInputStream(rawIn);
            
        } catch (IOException e) {
            throw new EvaluationException("Failed to start test driver process", e);
        }
        
        checkHeartbeat();
    }
    
    private void checkHeartbeat() throws EvaluationException {
        try  {
            out.writeObject("HEARTBEAT");
            out.flush();
        } catch (IOException e) {
            throw new EvaluationException("Communication with test driver process failed", e);
        }
        
        String answer = readResult();
        
        if (!answer.equals("alive")) {
            throw new EvaluationException("Test driver process does not reply with alive");
        }
        
        LOG.fine("Heartbeat of test driver process is alive");
    }
    
    private void stopProcess() {
        LOG.fine("Stopping test driver process"); 
        
        process.destroy();
        boolean terminated = ProcessRunner.untilNoInterruptedException(
                () -> process.waitFor(500, TimeUnit.MILLISECONDS));
        if (!terminated) {
            process.destroyForcibly();
        }
    }
    
    @Override
    public void close() throws EvaluationException {
        stopProcess();
        
        try {
            tempDirManager.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close temporary directory manager", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T> T readResult() throws EvaluationException, TimeoutException {
        try {
            if (timeoutMs > 0) {
                long t0 = System.currentTimeMillis();
                
                // ObjectInputStream.available() always seems to return 0, so ask the raw InputStream from the process
                // how many bytes are available
                while (System.currentTimeMillis() - t0 < timeoutMs && rawIn.available() == 0
                        && process.isAlive()) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
                
                if (!process.isAlive()) {
                    throw new EvaluationException("Test driver process died");
                }
                if (rawIn.available() == 0) {
                    stopProcess();
                    startProcess();
                    throw new TimeoutException("Test execution did not finish in " + timeoutMs + " ms");
                }
            }
            
            return (T) in.readObject();
            
        } catch (IOException | ClassNotFoundException e) {
            throw new EvaluationException("Failed to read result from test driver process", e);
        }
    }

    public List<TestResult> executeTestClass(String className) throws EvaluationException, TimeoutException {
        try {
            out.writeObject("CLASS");
            out.writeObject(className);
            out.flush();
            
            List<TestResult> result = readResult();
            
            LOG.fine(() -> result.size() + " tests run in test class " + className + ", "
                    + result.stream().filter(TestResult::isFailure).count() + " failures");
            
            return result;
            
        } catch (IOException e) {
            throw new EvaluationException("Communication with test driver process failed", e);
            
        } catch (TimeoutException e) {
            LOG.fine(() -> "Test class " + className + " timed out");
            throw e;
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
    
    public List<TestResultWithCoverage> executeTestClassWithCoverage(String className)
            throws EvaluationException, TimeoutException {
        
        try {
            ExecDumpClient jacocoClient = new ExecDumpClient();
            jacocoClient.setReset(true);
            jacocoClient.setDump(false);
            jacocoClient.dump("localhost", jacocoPort);
            
            out.writeObject("CLASS");
            out.writeObject(className);
            out.flush();
            
            List<TestResult> result = readResult();
            
            LOG.fine(() -> result.size() + " tests run in test class " + className + ", "
                    + result.stream().filter(TestResult::isFailure).count() + " failures");
            
            jacocoClient.setDump(true);
            ExecFileLoader loader = jacocoClient.dump("localhost", jacocoPort);
            
            ExecutionDataStore execData = loader.getExecutionDataStore();
            
            return result.stream()
                    .map(testResult -> new TestResultWithCoverage(testResult, execData))
                    .toList();
            
        } catch (IOException e) {
            throw new EvaluationException("Communication with test driver process failed", e);
            
        } catch (TimeoutException e) {
            LOG.fine(() -> "Test class " + className + " timed out");
            throw e;
        }
    }
    
    public TestResultWithCoverage executeTestMethodWithCoverage(String className, String methodName)
            throws EvaluationException, TimeoutException {
        
        try {
            ExecDumpClient jacocoClient = new ExecDumpClient();
            jacocoClient.setReset(true);
            jacocoClient.setDump(false);
            jacocoClient.dump("localhost", jacocoPort);
            
            out.writeObject("METHOD");
            out.writeObject(className);
            out.writeObject(methodName);
            out.flush();
            
            TestResult result = readResult();
            
            jacocoClient.setDump(true);
            ExecFileLoader loader = jacocoClient.dump("localhost", jacocoPort);
            
            return new TestResultWithCoverage(result, loader.getExecutionDataStore());
            
        } catch (IOException e) {
            throw new EvaluationException("Communication with test driver process failed", e);
            
        } catch (TimeoutException e) {
            LOG.fine(() -> "Test method " + className + "::" + methodName + " timed out");
            throw e;
        }
    }
    
    private List<String> createCommand(List<Path> classpath, boolean withCoverage)
            throws IOException {
        
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.getJvmBinaryPath());
        
        if (withCoverage) {
            command.add("-javaagent:" + JACOCO_AGENT.toAbsolutePath() + "=output=tcpserver,port=" + jacocoPort);
        }
        
        command.add("-Dfile.encoding=" + encoding.toString());
        command.add("-Djava.io.tmpdir=" + tempDirManager.createTemporaryDirectory());

        StringBuilder cp = new StringBuilder(GENESEER_TEST_DRIVER.toAbsolutePath().toString());
        for (Path element : classpath) {
            cp.append(File.pathSeparatorChar);
            cp.append(element.toString());
        }

        command.add("-cp");
        command.add(cp.toString());

        command.add("net.ssehub.program_repair.geneseer.evaluation.Runner");
        if (Configuration.INSTANCE.getDebugTestDriver()) {
            command.add("DEBUG");
        }
        return command;
    }
    
}
