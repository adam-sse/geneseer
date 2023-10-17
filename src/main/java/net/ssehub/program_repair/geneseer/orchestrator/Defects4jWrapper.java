package net.ssehub.program_repair.geneseer.orchestrator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;

public class Defects4jWrapper {
    
    public List<Bug> getBugs() throws IOException, IllegalArgumentException {
        ProcessRunner process = new ProcessRunner.Builder("defects4j", "pids")
                .captureOutput(true)
                .run();
        
        if (process.getExitCode() != 0) {
            throw new IOException("Failed to get projects: " + new String(process.getStderr()));
        }
        
        String[] projects = new String(process.getStdout()).split("\n");
        
        List<Bug> bugs = new LinkedList<>();
        for (String project : projects) {
            process = new ProcessRunner.Builder("defects4j", "bids", "-p", project)
                    .captureOutput(true)
                    .run();
            
            if (process.getExitCode() != 0) {
                throw new IOException("Failed to get bugs in project " + project + ": "
                        + new String(process.getStderr()));
            }
            
            String[] bugStrings = new String(process.getStdout()).split("\n");
            for (String bugString : bugStrings) {
                bugs.add(new Bug(project, Integer.parseInt(bugString)));
            }
        }
        
        return bugs;
    }

    public Project prepareProject(Bug bug) throws IOException, IllegalArgumentException {
        checkout(bug);
        Path checkoutDirectory = bug.getDirectory().toAbsolutePath();
        
        Path sourceDirectory;
        Path binDirectory;
        List<Path> compilationClasspath;
        List<Path> testExecutionClassPath;
        List<String> testClassNames;
        
        sourceDirectory = Path.of(exportProperty(checkoutDirectory, "dir.src.classes")[0]);
        
        binDirectory = Path.of(exportProperty(checkoutDirectory, "dir.bin.classes")[0]);
        Path absoluteBinDirectory = checkoutDirectory.resolve(binDirectory);
        
        compilationClasspath = getMultiplePathsProperty(checkoutDirectory, "cp.compile");
        compilationClasspath = normalizeAndRemoveBinDirectory(compilationClasspath, absoluteBinDirectory, checkoutDirectory);
        
        testExecutionClassPath = getMultiplePathsProperty(checkoutDirectory, "cp.test");
        testExecutionClassPath = normalizeAndRemoveBinDirectory(testExecutionClassPath, absoluteBinDirectory, checkoutDirectory);
        
        testClassNames = List.of(exportProperty(checkoutDirectory, "tests.all"));
        
        if (bug.project().equals("Closure")) {
            compilationClasspath.add(Path.of("lib/junit.jar"));
            compilationClasspath.add(Path.of("build/classes"));
            testExecutionClassPath.add(Path.of("build/classes"));
        }
        
        return new Project(checkoutDirectory, sourceDirectory, compilationClasspath,
                testExecutionClassPath, testClassNames);
    }
    
    private void checkout(Bug bug) throws IOException {
        if (!Files.exists(bug.getDirectory())) {
            ProcessRunner process = new ProcessRunner.Builder("defects4j", "checkout", "-p", bug.project(),
                    "-v", bug.bug() + "b", "-w", bug.getDirectory().toString())
                    .run();
            if (process.getExitCode() != 0) {
                throw new IOException("Failed to checkout " + bug);
            }
        }
    }

    private List<Path> getMultiplePathsProperty(Path checkoutDirectory, String propertyName)
            throws IOException, IllegalArgumentException {
        
        return Arrays.stream(exportProperty(checkoutDirectory, propertyName)[0].split(File.pathSeparator))
                .map(Path::of)
                .collect(Collectors.toList());
    }
    
    private List<Path> normalizeAndRemoveBinDirectory(List<Path> classpath, Path absoluteBinDirectory,
            Path checkoutDirectory) {
        return classpath.stream()
                .map(p -> checkoutDirectory.resolve(p))
                .filter(p -> !p.equals(absoluteBinDirectory))
                .map(p -> {
                    if (p.startsWith(checkoutDirectory)) {
                        return checkoutDirectory.relativize(p);
                    } else {
                        return p;
                    }
                })
                .collect(Collectors.toList());
    }
    
    private String[] exportProperty(Path checkoutDirectory, String propertyName) throws IOException {
        
        ProcessRunner process = new ProcessRunner.Builder("defects4j", "export", "-p", propertyName)
                .workingDirectory(checkoutDirectory)
                .captureOutput(true)
                .run();
        
        if (process.getExitCode() != 0) {
            throw new IOException("Failed to get property " + propertyName + "in " + checkoutDirectory + ": " 
                    + new String(process.getStderr()));
        }
        
        return new String(process.getStdout()).split("\n");
    }
    
}