package net.ssehub.program_repair.geneseer.evaluation;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;

class ProjectCompiler {
    
    private static final Logger LOG = Logger.getLogger(ProjectCompiler.class.getName());
    
    private List<Path> classpath;
    
    private Charset encoding;
    
    private Path sourceDirectory;
    
    private Path outputDirectory;
    
    private boolean logOutput;
    
    private Node previousWrittenAst;
    
    public ProjectCompiler(List<Path> classpath, Charset encoding, Path sourceDirectory, Path outputDirectory) {
        this.classpath = classpath;
        this.encoding = encoding;
        this.sourceDirectory = sourceDirectory;
        this.outputDirectory = outputDirectory;
    }
    
    public Path getSourceDirectory() {
        return sourceDirectory;
    }
    
    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public void compile(Node ast) throws CompilationException {
        try (Probe probe = Measurement.INSTANCE.start("compilation")) {
            Set<Path> filesToCompile = writeModifiedFiles(ast);
            List<String> command = buildCommand(filesToCompile);
            
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
            
            if (process.getExitCode() != 0) {
                throw new CompilationException("Compiler exited with " + process.getExitCode());
            }
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to run compiler process", e);
            throw new CompilationException("Failed to run compiler process", e);
        }
    }
    
    private Set<Path> writeModifiedFiles(Node newAst) throws CompilationException {
        Set<Path> modifiedFiles;
    
        try {
            if (previousWrittenAst == null || !getFilePaths(newAst).equals(getFilePaths(previousWrittenAst))) {
                deleteAllClassFiles(outputDirectory);
                Writer.write(newAst, sourceDirectory, encoding);
                modifiedFiles = Files.walk(sourceDirectory)
                        .filter(Files::isRegularFile)
                        .map(p -> sourceDirectory.relativize(p))
                        .filter(file -> file.getFileName().toString().endsWith(".java"))
                        .collect(Collectors.toSet());
                if (previousWrittenAst == null) {
                    LOG.fine(() -> "Initially compiling all " + modifiedFiles.size() + " files in source tree: "
                            + modifiedFiles);
                } else {
                    LOG.fine(() -> "File name set changed, compiling all " + modifiedFiles.size()
                            + " files in source tree: " + modifiedFiles);
                }
                
            } else {
                Map<Path, Node> previousFilesByPath = new HashMap<>();
                for (Node fileNode : previousWrittenAst.childIterator()) {
                    previousFilesByPath.put((Path) fileNode.getMetadata(Metadata.FILE_NAME), fileNode);
                }
                
                modifiedFiles = new HashSet<>();
                for (int i = 0; i < newAst.childCount(); i++) {
                    Node newFile = newAst.get(i);
                    Path path = (Path) newFile.getMetadata(Metadata.FILE_NAME);
                    if (!newFile.equals(previousFilesByPath.get(path))) {
                        Writer.writeSingleFile(newFile, sourceDirectory, encoding);
                        modifiedFiles.add(path);
                    }
                }
                
                LOG.fine(() -> "Only compiling " + modifiedFiles.size() + " modified files: " + modifiedFiles);
            }
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to run compiler process", e);
            previousWrittenAst = null;
            throw new CompilationException("Failed to write source files", e);
        }
        
        previousWrittenAst = newAst;
        return modifiedFiles;
    }

    private static void deleteAllClassFiles(Path directory) throws IOException {
        try {
            Files.walk(directory)
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".class"))
                    .forEach(file -> {
                        try {
                            Files.delete(file);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
    
    private static Set<Path> getFilePaths(Node ast) {
        Set<Path> paths = new HashSet<>(ast.childCount());
        for (Node fileNode : ast.childIterator()) {
            paths.add((Path) fileNode.getMetadata(Metadata.FILE_NAME));
        }
        return paths;
    }
    
    private List<String> buildCommand(Set<Path> filesToCompile)
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
            command.add(outputDirectory.toString() + File.pathSeparator + classpath.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        
        filesToCompile.stream()
                .map(Path::toString)
                .forEach(command::add);
        
        return command;
    }
    
    public void setLogOutput(boolean logOutput) {
        this.logOutput = logOutput;
    }
    
}
