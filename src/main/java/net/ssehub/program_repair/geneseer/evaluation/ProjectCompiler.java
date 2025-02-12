package net.ssehub.program_repair.geneseer.evaluation;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;

class ProjectCompiler {
    
    private static final Logger LOG = Logger.getLogger(ProjectCompiler.class.getName());
    
    private List<Path> classpath;
    
    private Charset encoding;
    
    private boolean logOutput;
    
    public ProjectCompiler(List<Path> classpath, Charset encoding) {
        this.classpath = classpath;
        this.encoding = encoding;
    }

    public void compile(Path sourceDirectory, Path outputDirectory) throws CompilationException {
        try (Probe probe = Measurement.INSTANCE.start("compilation")) {
            List<String> command = buildCommand(sourceDirectory, outputDirectory, classpath);
            
            LOG.finer(() -> {
                String log;
                if (command.size() <= 10) {
                    log = "Running " + command;
                } else {
                    log = "Running " + Stream.concat(command.stream().limit(10), Stream.of("<...>")).toList();
                }
                return log;
            });
            ProcessRunner process = new ProcessRunner.Builder(command)
                        .workingDirectory(sourceDirectory)
                        .captureOutput(logOutput)
                        .run();
            
            
            LOG.fine(() -> "Compilation " + (process.getExitCode() == 0 ? "" : "not ") + "successful");
            if (logOutput) {
                String stdout = new String(process.getStdout());
                String stderr = new String(process.getStderr());
                if (!stdout.isEmpty()) {
                    LOG.fine(() -> "Compiler stdout:\n" + stdout);
                }
                if (!stderr.isEmpty()) {
                    LOG.fine(() -> "Compiler stderr:\n" + stderr);
                }
            }
            
            try {
                FileUtils.copyAllNonJavaSourceFiles(sourceDirectory, outputDirectory);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to copy non-Java source files", e);
            }
            
            if (process.getExitCode() != 0) {
                throw new CompilationException("Compiler exited with " + process.getExitCode());
            }
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to run compiler process", e);
            throw new CompilationException("Failed to run compiler process", e);
        }
    }
    
    private List<String> buildCommand(Path sourceDirectory, Path outputDirectory, List<Path> classpath)
            throws IOException {
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.setup().javaCompilerBinaryPath());
        
        command.add("-nowarn");
        
        command.add("-encoding");
        command.add(encoding.toString());
        
        command.add("-d");
        command.add(outputDirectory.toString());
        
        if (!classpath.isEmpty()) {
            command.add("-cp");
            command.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        
        Files.walk(sourceDirectory)
                .filter(Files::isRegularFile)
                .map(p -> sourceDirectory.relativize(p))
                .map(Path::toString)
                .filter(file -> file.endsWith(".java"))
                .forEach(command::add);
        
        return command;
    }
    
    public void setLogOutput(boolean logOutput) {
        this.logOutput = logOutput;
    }
    
}
