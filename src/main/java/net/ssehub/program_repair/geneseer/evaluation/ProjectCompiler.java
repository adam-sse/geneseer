package net.ssehub.program_repair.geneseer.evaluation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;

public class ProjectCompiler {
    
    private static final Logger LOG = Logger.getLogger(ProjectCompiler.class.getName());
    
    private List<Path> classpath;
    
    private boolean logOutput;
    
    public ProjectCompiler(List<Path> classpath) {
        this.classpath = classpath;
    }

    public boolean compile(Path sourceDirectory, Path outputDirectory) {
        try (Probe probe = Measurement.INSTANCE.start("compilation")) {
            List<String> command;
            try {
                command = buildCommand(sourceDirectory, outputDirectory, classpath);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to find java source files", e);
                return false;
            }
            
            LOG.fine(() -> "Running " + command);
            ProcessRunner process;
            try {
                process = new ProcessRunner.Builder(command)
                        .workingDirectory(sourceDirectory)
                        .captureOutput(logOutput)
                        .run();
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to start compiler process", e);
                return false;
            }
            
            
            LOG.info(() -> "Compilation " + (process.getExitCode() == 0 ? "" : "not ") + "successful");
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
            
            return process.getExitCode() == 0;
        }
    }
    
    private List<String> buildCommand(Path sourceDirectory, Path outputDirectory, List<Path> classpath)
            throws IOException {
        List<String> command = new LinkedList<>();
        command.add(Configuration.INSTANCE.getJavaCompilerBinaryPath());
        
        command.add("-nowarn");
        
        command.add("-encoding");
        command.add(Configuration.INSTANCE.getEncoding().toString());
        
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
