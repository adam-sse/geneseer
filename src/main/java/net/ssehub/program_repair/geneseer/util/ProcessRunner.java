package net.ssehub.program_repair.geneseer.util;

import java.io.IOException;
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
        
        public ProcessRunner run() throws IOException {
            return new ProcessRunner(command, workingDirectory, timeoutMs);
        }
        
    }
    
    private ProcessRunner(List<String> command, Path workingDirectory, long timeoutMs) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        
        Process process = builder.start();
        
        Thread stdoutCapture = new Thread(() -> {
            try {
                this.stdout = process.getInputStream().readAllBytes();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to read process stdout", e);
            }
        }, "stdout-capture");
        Thread stderrCapture = new Thread(() -> {
            try {
                this.stderr = process.getErrorStream().readAllBytes();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to read process stderr", e);
            }
        }, "stderr-capture");
        
        stdoutCapture.setDaemon(true);
        stderrCapture.setDaemon(true);
        stdoutCapture.start();
        stderrCapture.start();

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
        
        untilNoInterruptedException(() -> {
            stdoutCapture.join();
            return null;
        });
        untilNoInterruptedException(() -> {
            stderrCapture.join();
            return null;
        });
    }
    
    private interface Interruptable<T> {
        T run() throws InterruptedException;
    }
    
    private static <T> T untilNoInterruptedException(Interruptable<T> function) {
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
