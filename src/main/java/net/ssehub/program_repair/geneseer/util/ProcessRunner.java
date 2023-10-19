package net.ssehub.program_repair.geneseer.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProcessRunner {
    
    private static final Logger LOG = Logger.getLogger(ProcessRunner.class.getName());

    private int exitCode;
    
    private byte[] stdout;
    
    private byte[] stderr;
    
    public static class Builder {
        
        private List<String> command;
        
        private Path workingDirectory = null;
        
        private long timeoutMs = -1;
        
        private boolean captureOutput = true;
        
        public Builder(List<String> command) {
            this.command = command;
        }
        
        public Builder(String... command) {
            this.command = Arrays.asList(command);
        }
        
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }
        
        public Builder timeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }
        
        public Builder captureOutput(boolean captureOutput) {
            this.captureOutput = captureOutput;
            return this;
        }
        
        public ProcessRunner run() throws IOException {
            return new ProcessRunner(command, workingDirectory, timeoutMs, captureOutput);
        }
        
    }
    
    public static class CaptureThread extends Thread {
        
        private InputStream input;
        
        private String name;
        
        private byte[] output;
        
        public CaptureThread(InputStream input, String streamName) {
            setName(streamName + " capture");
            this.input = input;
            this.name = streamName;
        }
        
        public byte[] getOutput() {
            return output;
        }
        
        @Override
        public void run() {
            try {
                output = input.readAllBytes();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to read " + name, e);
            }
        }
        
    }
    
    private ProcessRunner(List<String> command, Path workingDirectory, long timeoutMs, boolean captureOutput)
            throws IOException {
        
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        
        if (!captureOutput) {
            builder.redirectOutput(Redirect.DISCARD);
            builder.redirectError(Redirect.DISCARD);
        }
        
        Process process = builder.start();
        
        CaptureThread stdoutCapture;
        CaptureThread stderrCapture;
        if (captureOutput) {
            stdoutCapture = new CaptureThread(process.getInputStream(), "process stdout");
            stderrCapture = new CaptureThread(process.getErrorStream(), "process stderr");
            
            stdoutCapture.setDaemon(true);
            stderrCapture.setDaemon(true);
            stdoutCapture.start();
            stderrCapture.start();
            
        } else {
            stdoutCapture = null;
            stderrCapture = null;
        }

        if (timeoutMs > 0) {
            boolean terminated = untilNoInterruptedException(() -> process.waitFor(timeoutMs, TimeUnit.MILLISECONDS));
            
            if (terminated) {
                this.exitCode = process.exitValue();
            } else {
                process.destroy();
                terminated = untilNoInterruptedException(() -> process.waitFor(500, TimeUnit.MILLISECONDS));
                if (!terminated) {
                    process.destroyForcibly();
                }
                throw new IOException("Process did not terminate within " + timeoutMs + " milliseconds");
            }
            
        } else {
            this.exitCode = untilNoInterruptedException(() -> process.waitFor());
        }
        if (captureOutput) {
            untilNoInterruptedException(() -> {
                stdoutCapture.join();
                this.stdout = stdoutCapture.getOutput();
                return null;
            });
            untilNoInterruptedException(() -> {
                stderrCapture.join();
                this.stderr = stderrCapture.getOutput();
                return null;
            });
        }
    }
    
    public interface Interruptable<T> {
        T run() throws InterruptedException;
    }
    
    public static <T> T untilNoInterruptedException(Interruptable<T> function) {
        T result = null;
        boolean waitSuccess = false;
        do {
            try {
                result = function.run();
                waitSuccess = true;
            } catch (InterruptedException e) {
            }
        } while (!waitSuccess);
        return result;
    }
    
    public int getExitCode() {
        return exitCode;
    }
    
    public byte[] getStdout() {
        return stdout;
    }
    
    public byte[] getStderr() {
        return stderr;
    }
    
}
