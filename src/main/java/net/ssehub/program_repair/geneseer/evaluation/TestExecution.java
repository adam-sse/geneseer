package net.ssehub.program_repair.geneseer.evaluation;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.ProcessManager;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.ProcessRunner.CaptureThread;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

class TestExecution implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TestExecution.class.getName());
    
    private static final Path GENESEER_TEST_DRIVER;
    
    private static final Path JACOCO_AGENT;

    static {
        try {
            @SuppressWarnings("resource") // closed in shutdown hook of TemporaryDirectoryManager
            TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager();
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
    
    private boolean withJacocoAgent;
    
    private Process process;
    
    private ObjectInputStream in;
    
    private InputStream rawIn;
    
    private ObjectOutputStream out;
    
    private CaptureThread stderrCapture;
    
    private long timeoutMs = -1;
    
    private boolean splitTestClassLoaders;
    
    private int jacocoPort;
    
    public TestExecution(Path workingDirectory, List<Path> classpath, Charset encoding, boolean withJacocoAgent,
            boolean splitTestClassLoaders) throws TestExecutionException {
        tempDirManager = new TemporaryDirectoryManager();
        this.workingDirectory = workingDirectory;
        this.classpath = classpath;
        this.encoding = encoding;
        this.withJacocoAgent = withJacocoAgent;
        this.splitTestClassLoaders = splitTestClassLoaders;
        
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
    
    private void startProcess() throws TestExecutionException {
        try {
            if (withJacocoAgent) {
                jacocoPort = generateRandomPort();
            }
            
            List<String> command = createCommand(classpath, withJacocoAgent);
            LOG.finer(() -> {
                List<String> shortened = new LinkedList<>(command);
                shortened.set(shortened.indexOf("-cp") + 1, "<...>");
                return "Starting test driver process: " + shortened + " in " + workingDirectory;
            });
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.environment().put("TZ", "America/Los_Angeles");
            if (Configuration.INSTANCE.setup().debugTestDriver()) {
                builder.redirectError(Redirect.INHERIT);
            } else {
                builder.redirectError(Redirect.PIPE);
            }
            
            process = builder.start();
            ProcessManager.INSTANCE.trackProcess(process);
            out = new ObjectOutputStream(process.getOutputStream());
            rawIn = process.getInputStream();
            byte[] header = rawIn.readNBytes(4);
            if (!Arrays.equals(header, new byte[] {(byte) 0xAC, (byte) 0xED, 0x00, 0x05}) ) {
                int available = rawIn.available();
                byte[] availableOutput = new byte[header.length + available];
                System.arraycopy(header, 0, availableOutput, 0, 4);
                int read = rawIn.read(availableOutput, 4, available);
                String str = new String(availableOutput, 0, 4 + read);
                LOG.severe(() -> "Did not get Java serialized stream, but: " + str);
                throw new IOException("Java process did not start Java serialized stream");
            }
            in = new ObjectInputStream(new SequenceInputStream(new ByteArrayInputStream(header), rawIn));
            
            if (!Configuration.INSTANCE.setup().debugTestDriver()) {
                stderrCapture = new CaptureThread(process.getErrorStream(), "test-driver stderr");
                stderrCapture.start();
            }
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to start test driver process", e);
            throw new TestExecutionException("Failed to start test driver process", e);
        }
        
        checkHeartbeat();
    }
    
    private void checkHeartbeat() throws TestExecutionException {
        try  {
            out.writeObject("HEARTBEAT");
            out.flush();
        } catch (IOException e) {
            throw new TestExecutionException("Communication with test driver process failed", e);
        }
        
        String answer = readResult();
        
        if (!answer.equals("alive")) {
            throw new TestExecutionException("Test driver process does not reply with alive");
        }
        
        LOG.finer("Heartbeat of test driver process is alive");
    }
    
    private void stopProcess() {
        LOG.finer("Stopping test driver process"); 
        
        try {
            out.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close stream", e);
            process.destroy();
        }
        boolean terminated = ProcessRunner.untilNoInterruptedException(
                () -> process.waitFor(8, TimeUnit.SECONDS));
        if (!terminated) {
            LOG.warning("Forcibly stopping test driver process");
            process.destroyForcibly();
        }
        
        if (stderrCapture != null) {
            ProcessRunner.untilNoInterruptedException(() -> {
                stderrCapture.join();
                return 0;
            });
            String stderr = new String(stderrCapture.getOutput());
            if (!stderr.isEmpty()) {
                LOG.warning(() -> "Test driver stderr:\n" + stderr);
            }
        }
    }
    
    @Override
    public void close() {
        stopProcess();
        
        try {
            tempDirManager.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close temporary directory manager", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private <T> T readResult() throws TestExecutionException {
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
                    throw new TestExecutionException("Test driver process died");
                }
                if (rawIn.available() == 0) {
                    stopProcess();
                    startProcess();
                    throw new TestTimeoutException("Test execution did not finish in " + timeoutMs + " ms");
                }
            }
            
            return (T) in.readObject();
            
        } catch (IOException | ClassNotFoundException e) {
            throw new TestExecutionException("Failed to read result from test driver process", e);
        }
    }

    public List<TestResult> executeTestClass(String className) throws TestExecutionException {
        try {
            out.writeObject("CLASS");
            out.writeObject(className);
            out.flush();
            
            List<TestResult> result = readResult();
            LOG.fine(() -> result.size() + " tests run in test class " + className + ", "
                    + result.stream().filter(TestResult::isFailure).count() + " failures");
            
            return result;
            
        } catch (IOException e) {
            throw new TestExecutionException("Communication with test driver process failed", e);
            
        } catch (TestTimeoutException e) {
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
            throws TestExecutionException {
        
        try {
            ExecDumpClient jacocoClient = new ExecDumpClient();
            jacocoClient.setReset(true);
            jacocoClient.setDump(false);
            jacocoClient.dump("localhost", jacocoPort);
            
            out.writeObject("METHODS");
            out.writeObject(className);
            out.flush();
            
            Map<String, ExecutionDataStore> coverages = new HashMap<>();
            
            Object resultState;
            do {
                resultState = readResult();
                if ("TEST_FINISHED".equals(resultState)) {
                    String implementingClass = readResult();
                    String testMethod = readResult();
                    
                    jacocoClient.setDump(true);
                    ExecFileLoader loader = jacocoClient.dump("localhost", jacocoPort);
                    ExecutionDataStore execata = loader.getExecutionDataStore();
                    coverages.put(className + "::" + testMethod
                            + (!className.equals(implementingClass) ? "@" + implementingClass : ""), execata);
                    
                    out.writeObject("CONTINUE");
                    out.flush();
                    
                } else if (!"DONE".equals(resultState)) {
                    throw new TestExecutionException("Got invalid state from test driver: " + resultState);
                }
            } while (!"DONE".equals(resultState));
            
            List<TestResult> result = readResult();
            LOG.fine(() -> result.size() + " tests run in test class " + className + ", "
                    + result.stream().filter(TestResult::isFailure).count() + " failures");
            
            List<TestResultWithCoverage> coverageResult = new ArrayList<>(result.size());
            for (TestResult test : result) {
                ExecutionDataStore coverage = coverages.remove(test.getIdentifier());
                if (coverage == null) {
                    throw new TestExecutionException("Did not find coverage for test " + test);
                }
                coverageResult.add(new TestResultWithCoverage(test, coverage));
            }
            if (!coverages.isEmpty()) {
                throw new TestExecutionException("Did not get test result for coverages: " + coverages.keySet());
            }
            return coverageResult;
            
        } catch (IOException e) {
            throw new TestExecutionException("Communication with test driver process failed", e);
            
        } catch (TestTimeoutException e) {
            LOG.fine(() -> "Test class " + className + " timed out");
            throw e;
        }
    }
    
    private List<String> createCommand(List<Path> classpath, boolean withJacocoAgent)
            throws IOException {
        
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.setup().jvmBinaryPath());
        
        if (withJacocoAgent) {
            command.add("-javaagent:" + JACOCO_AGENT.toAbsolutePath()
                    + "=excludes=*,output=tcpserver,port=" + jacocoPort);
        }
        
        command.add("-Dfile.encoding=" + encoding.toString());
        command.add("-Djava.io.tmpdir=" + tempDirManager.createTemporaryDirectory());
        command.add("-XX:-UsePerfData");
        if (Configuration.INSTANCE.setup().debugTestDriver()) {
            command.add("-Dgeneseer.logTimeZone=" + ZoneId.systemDefault().getId());
        }
        
        StringBuilder cp = new StringBuilder(GENESEER_TEST_DRIVER.toAbsolutePath().toString());
        for (Path element : classpath) {
            cp.append(File.pathSeparatorChar);
            cp.append(element.toString());
        }

        command.add("-cp");
        command.add(cp.toString());

        command.add("net.ssehub.program_repair.geneseer.evaluation.TestDriver");
        if (!splitTestClassLoaders) {
            command.add("--no-per-test-classloader");
        }
        if (Configuration.INSTANCE.setup().debugTestDriver()) {
            command.add("--debug");
        }
        return command;
    }
    
}
