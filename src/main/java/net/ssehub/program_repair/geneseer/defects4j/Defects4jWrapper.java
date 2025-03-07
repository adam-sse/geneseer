package net.ssehub.program_repair.geneseer.defects4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import net.ssehub.program_repair.geneseer.Configuration.SetupConfiguration.TestsToRun;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;

class Defects4jWrapper {
    
    private static final Logger LOG = Logger.getLogger(Defects4jWrapper.class.getName());
    
    private Path defects4jHome;
    
    public Defects4jWrapper(Path defects4jHome) throws IllegalArgumentException {
        this.defects4jHome = defects4jHome;
        if (!Files.isRegularFile(Path.of(getDefects4jBinary()))) {
            throw new IllegalArgumentException(getDefects4jBinary() + " does not exist");
        }
    }
    
    public List<Bug> getAllBugs() throws IOException, IllegalArgumentException {
        ProcessRunner process = new ProcessRunner.Builder(getDefects4jBinary(), "pids")
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
        ProcessRunner process = new ProcessRunner.Builder(getDefects4jBinary(), "bids", "-p", project)
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
        Path checkoutDirectory = bug.getDirectory().toAbsolutePath();
        if (!Files.isDirectory(checkoutDirectory)) {
            checkout(bug, checkoutDirectory, Version.BUGGY, true);
        }
        
        Path sourceDirectory;
        Path binDirectory;
        List<Path> compilationClasspath;
        List<Path> testExecutionClassPath;
        List<String> testClassNames;
        
        sourceDirectory = getRelativeSourceDirectory(checkoutDirectory);
        
        binDirectory = Path.of(exportProperty(checkoutDirectory, "dir.bin.classes")[0]);
        Path absoluteBinDirectory = checkoutDirectory.resolve(binDirectory);
        
        compilationClasspath = getMultiplePathsProperty(checkoutDirectory, "cp.compile");
        compilationClasspath = normalizeAndRemoveBinDirectory(compilationClasspath, absoluteBinDirectory,
                checkoutDirectory);
        
        testExecutionClassPath = getMultiplePathsProperty(checkoutDirectory, "cp.test");
        testExecutionClassPath = normalizeAndRemoveBinDirectory(testExecutionClassPath, absoluteBinDirectory,
                checkoutDirectory);
        testExecutionClassPath = removeJunit(testExecutionClassPath);
        
        testClassNames = new LinkedList<>(List.of(exportProperty(checkoutDirectory,
                Configuration.INSTANCE.setup().testsToRun() == TestsToRun.ALL_TESTS ? "tests.all" : "tests.relevant")));

        applyProjectSpecificFixes(bug, checkoutDirectory, compilationClasspath, testExecutionClassPath, testClassNames);
        
        Project project = new Project(checkoutDirectory, sourceDirectory, compilationClasspath,
                testExecutionClassPath, testClassNames);
        if (bug.project().equals("Lang")) {
            project.setEncoding(StandardCharsets.ISO_8859_1);
        }
        return project;
    }

    public Set<String> getFailingTests(Bug bug) throws IOException {
        return Arrays.stream(exportProperty(bug.getDirectory(), "tests.trigger")).collect(Collectors.toSet());
    }
    
    public Path getBinDirectory(Bug bug) throws IOException {
        return Path.of(exportProperty(bug.getDirectory(), "dir.bin.classes")[0]);
    }
    
    public Path getTestSourceDirectory(Bug bug) throws IOException {
        return Path.of(exportProperty(bug.getDirectory(), "dir.src.tests")[0]);
    }
    
    public Path getTestBinDirectory(Bug bug) throws IOException {
        return Path.of(exportProperty(bug.getDirectory(), "dir.bin.tests")[0]);
    }
    
    public enum Version {
        FIXED('f'), BUGGY('b');
        private char identifier;
        private Version(char identifier) {
            this.identifier = identifier;
        }
    }
    
    
    public void checkout(Bug bug, Path target, Version version, boolean compile) throws IOException {
        if (!Files.isDirectory(target)) {
            Files.createDirectories(target);
        }
        ProcessRunner process = new ProcessRunner.Builder(getDefects4jBinary(), "checkout", "-p", bug.project(),
                "-v", Integer.toString(bug.bug()) + version.identifier, "-w", target.toString())
                .captureOutput(true)
                .run();
        checkForError(process, "Failed to checkout " + bug);
        
        if (compile) {
            process = new ProcessRunner.Builder(getDefects4jBinary(), "compile")
                    .workingDirectory(target)
                    .captureOutput(true)
                    .run();
            checkForError(process, "Failed to compile " + bug);
        }
    }

    private static void checkForError(ProcessRunner process, String message) throws IOException {
        if (process.getExitCode() != 0) {
            String stdout = new String(process.getStdout());
            String stderr = new String(process.getStderr());
            if (!stdout.isEmpty()) {
                LOG.warning(() -> "defects4j stdout:\n" + stdout);
            }
            if (!stderr.isEmpty()) {
                LOG.warning(() -> "defects4j stdout:\n" + stderr);
            }
            throw new IOException(message);
        }
    }
    
    public Path getRelativeSourceDirectory(Path checkoutDirectory) throws IOException {
        return Path.of(exportProperty(checkoutDirectory, "dir.src.classes")[0]);
    }
    
    public Path getRelativeBinDirectory(Path checkoutDirectory) throws IOException {
        return Path.of(exportProperty(checkoutDirectory, "dir.bin.classes")[0]);
    }
    
    public Path getRelativeTestSourceDirectory(Path checkoutDirectory) throws IOException {
        return Path.of(exportProperty(checkoutDirectory, "dir.src.tests")[0]);
    }
    
    public Path getRelativeTestBinDirectory(Path checkoutDirectory) throws IOException {
        return Path.of(exportProperty(checkoutDirectory, "dir.bin.tests")[0]);
    }

    private List<Path> getMultiplePathsProperty(Path checkoutDirectory, String propertyName)
            throws IOException {
        
        return Arrays.stream(exportProperty(checkoutDirectory, propertyName)[0].split(File.pathSeparator))
                .map(Path::of)
                .collect(Collectors.toList());
    }
    
    private String[] exportProperty(Path checkoutDirectory, String propertyName) throws IOException {
        
        ProcessRunner process = new ProcessRunner.Builder(getDefects4jBinary(), "export", "-p", propertyName)
                .workingDirectory(checkoutDirectory)
                .captureOutput(true)
                .run();
        
        if (process.getExitCode() != 0) {
            throw new IOException("Failed to get property " + propertyName + " in " + checkoutDirectory + ": " 
                    + new String(process.getStderr()));
        }
        
        return new String(process.getStdout()).split("\n");
    }
    
    private static List<Path> normalizeAndRemoveBinDirectory(List<Path> classpath, Path absoluteBinDirectory,
            Path checkoutDirectory) {
        return classpath.stream()
                .map(p -> checkoutDirectory.resolve(p))
                .filter(p -> !p.equals(absoluteBinDirectory))
                .map(p -> p.startsWith(checkoutDirectory) ? checkoutDirectory.relativize(p) : p)
                .collect(Collectors.toList());
    }
    
    private static List<Path> removeJunit(List<Path> classpath) {
        return classpath.stream()
                .filter(entry -> {
                    String name = entry.getFileName().toString();
                    boolean isJunit = name.matches("junit(-[0-9\\.\\-]+|4-core|4-legacy)?\\.jar");
                    if (isJunit) {
                        LOG.fine(() -> "Removing junit jar: " + entry);
                    }
                    return !isJunit;
                })
                .collect(Collectors.toList());
    }

    private void applyProjectSpecificFixes(Bug bug, Path checkoutDirectory, List<Path> compilationClasspath,
            List<Path> testExecutionClassPath, List<String> testClassNames) {
        switch (bug.project()) {
        case "Cli":
            testExecutionClassPath.remove(Path.of("file"));
            testExecutionClassPath.remove(Path.of("${test.classes.dir}"));
            testExecutionClassPath.remove(Path.of("${classes.dir}"));
            break;
            
        case "Closure":
            addIfExists(checkoutDirectory, Path.of("lib/junit.jar"), compilationClasspath);
            addIfExists(checkoutDirectory, Path.of("lib/json.jar"), compilationClasspath);
            compilationClasspath.add(Path.of("build/classes"));
            testExecutionClassPath.add(Path.of("build/classes"));
            break;
            
        case "Compress":
            testExecutionClassPath.remove(Path.of("${dependency.jar}"));
            break;
            
        case "JacksonXml":
            compilationClasspath.removeIf(p -> p.getFileName().toString().equals("stax-api-1.0-2.jar"));
            testExecutionClassPath.removeIf(p -> p.getFileName().toString().equals("stax-api-1.0-2.jar"));
            break;
            
        case "Mockito":
            compilationClasspath.add(defects4jResource("framework/projects/lib/junit-4.11.jar"));
            compilationClasspath.removeIf(p -> p.endsWith("hamcrest-all-1.3.jar"));
            testExecutionClassPath.removeIf(p -> p.endsWith("hamcrest-all-1.3.jar"));
            addIfExists(checkoutDirectory, Path.of("build/classes/main"), testExecutionClassPath);
            break;
            
        case "Math":
            if (bug.bug() >= 100) {
                compilationClasspath.add(defects4jResource("framework/projects/Math/lib/commons-discovery-0.5.jar"));
            }
            if (bug.bug() < 85) {
                testExecutionClassPath.add(Path.of("src/main/resources"));
            }
            testClassNames.remove("org.apache.commons.math3.genetics.FixedElapsedTimeTest");
            break;
            
        case "Time":
            copyTimeTzData(bug, checkoutDirectory);
            break;
            
        default:
            break;
        }
    }
    
    private static void addIfExists(Path dir, Path toAdd, List<Path> list) {
        if (Files.exists(dir.resolve(toAdd))) {
            list.add(toAdd);
        }
    }
    
    private Path defects4jResource(String pathInDefects4jRoot) {
        return defects4jHome.resolve(pathInDefects4jRoot).toAbsolutePath();
    }

    private static void copyTimeTzData(Bug bug, Path checkoutDirectory) {
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
    
    private String getDefects4jBinary() {
        return defects4jHome.resolve("framework/bin/defects4j").toAbsolutePath().toString();
    }
    
}
