package net.ssehub.program_repair.geneseer.orchestrator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Configuration.TestsToRun;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;

public class Defects4jWrapper {
    
    private static final Logger LOG = Logger.getLogger(Defects4jWrapper.class.getName());
    
    public List<Bug> getAllBugs() throws IOException, IllegalArgumentException {
        ProcessRunner process = new ProcessRunner.Builder("defects4j", "pids")
                .captureOutput(true)
                .run();
        
        if (process.getExitCode() != 0) {
            throw new IOException("Failed to get projects: " + new String(process.getStderr()));
        }
        
        String[] projects = new String(process.getStdout()).split("\n");
        
        List<Bug> bugs = new LinkedList<>();
        for (String project : projects) {
            bugs.addAll(getBugsOfProject(project));
        }
        
        return bugs;
    }
    
    public List<Bug> getBugsOfProject(String project) throws IOException, IllegalArgumentException {
        ProcessRunner process = new ProcessRunner.Builder("defects4j", "bids", "-p", project)
                .captureOutput(true)
                .run();
        
        if (process.getExitCode() != 0) {
            throw new IOException("Failed to get bugs in project " + project + ": "
                    + new String(process.getStderr()));
        }
        
        List<Bug> bugs = new LinkedList<>();
        String[] bugStrings = new String(process.getStdout()).split("\n");
        for (String bugString : bugStrings) {
            bugs.add(new Bug(project, Integer.parseInt(bugString)));
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
        compilationClasspath = normalizeAndRemoveBinDirectory(compilationClasspath, absoluteBinDirectory,
                checkoutDirectory);
        
        testExecutionClassPath = getMultiplePathsProperty(checkoutDirectory, "cp.test");
        testExecutionClassPath = normalizeAndRemoveBinDirectory(testExecutionClassPath, absoluteBinDirectory,
                checkoutDirectory);
        
        testClassNames = new LinkedList<>(List.of(exportProperty(checkoutDirectory,
                Configuration.INSTANCE.getTestsToRun() == TestsToRun.ALL_TESTS ? "tests.all" : "tests.relevant")));
        
        if (bug.project().equals("Closure")) {
            compilationClasspath.add(Path.of("lib/junit.jar"));
            compilationClasspath.add(Path.of("lib/json.jar"));
            compilationClasspath.add(Path.of("build/classes"));
            testExecutionClassPath.add(Path.of("build/classes"));
            
        } else if (bug.project().equals("Mockito")) {
            compilationClasspath.add(Path.of("/home/krafczyk/defects4j/framework/projects/lib/junit-4.11.jar"));
            compilationClasspath.removeIf(p -> p.endsWith("hamcrest-all-1.3.jar"));
            testExecutionClassPath.removeIf(p -> p.endsWith("hamcrest-all-1.3.jar"));
            testExecutionClassPath.add(Path.of("build/classes/main"));
            
        } else if (bug.project().equals("Math") && bug.bug() >= 100) {
            compilationClasspath.add(Path.of(
                    "/home/krafczyk/defects4j/framework/projects/Math/lib/commons-discovery-0.5.jar"));
            
        } else if (bug.project().equals("Time")) {
            copyTimeTzData(bug, checkoutDirectory);
        }
        
        return new Project(checkoutDirectory, sourceDirectory, compilationClasspath,
                testExecutionClassPath, testClassNames);
    }
    
    public Set<String> getFailingTests(Bug bug) throws IOException {
        return Arrays.stream(exportProperty(bug.getDirectory(), "tests.trigger")).collect(Collectors.toSet());
    }
    
    private void checkout(Bug bug) throws IOException {
        if (!Files.exists(bug.getDirectory())) {
            ProcessRunner process = new ProcessRunner.Builder("defects4j", "checkout", "-p", bug.project(),
                    "-v", bug.bug() + "b", "-w", bug.getDirectory().toString())
                    .run();
            if (process.getExitCode() != 0) {
                throw new IOException("Failed to checkout " + bug);
            }
            
            process = new ProcessRunner.Builder("defects4j", "compile")
                    .workingDirectory(bug.getDirectory())
                    .run();
            if (process.getExitCode() != 0) {
                throw new IOException("Failed to compile " + bug);
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
                .map(p -> p.startsWith(checkoutDirectory) ? checkoutDirectory.relativize(p) : p)
                .collect(Collectors.toList());
    }
    
    private String[] exportProperty(Path checkoutDirectory, String propertyName) throws IOException {
        
        ProcessRunner process = new ProcessRunner.Builder("defects4j", "export", "-p", propertyName)
                .workingDirectory(checkoutDirectory)
                .captureOutput(true)
                .run();
        
        if (process.getExitCode() != 0) {
            throw new IOException("Failed to get property " + propertyName + " in " + checkoutDirectory + ": " 
                    + new String(process.getStderr()));
        }
        
        return new String(process.getStdout()).split("\n");
    }
    
    private void copyTimeTzData(Bug bug, Path checkoutDirectory) {
        Path from;
        if (bug.bug() <= 11) {
            from = checkoutDirectory.resolve("target/classes/org/joda/time/tz/data");
        } else {
            from = checkoutDirectory.resolve("build/classes/org/joda/time/tz/data");
        }
        if (Files.isDirectory(from)) {
            Path to = checkoutDirectory.resolve("src/main/java/org/joda/time/tz/data");
            
            if (!Files.isDirectory(to)) {
                try {
                    Files.createDirectories(to);
                    FileUtils.copyAllNonJavaSourceFiles(from, to);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to copy timezone data in Time project", e);
                }
            }
            
        } else {
            LOG.warning("Directory target/classes/org/joda/time/tz/data does not exist in Time project");
        }
    }
    
}
