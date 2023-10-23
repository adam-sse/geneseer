package net.ssehub.program_repair.geneseer;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import net.ssehub.program_repair.geneseer.util.CliArguments;

/**
 * Represents a project the project that is under repair.
 * 
 * @author Adam
 */
public class Project {
    
    private static final Logger LOG = Logger.getLogger(Project.class.getName());

    private Path projectDirectory;
    
    private Path sourceDirectory;
    
    private List<Path> compilationClasspath;
    
    private List<Path> testExecutionClassPath;
    
    private List<String> testClassNames;
    
    private Charset encoding;
    
    /**
     * @param projectDirectory The root directory of the project.
     * @param sourceDirectory The base directory where the Java source code files are located. May be relative to
     *      {@code projectDirectory}.
     * @param compilationClasspath The classpath of dependencies required to compile the classes in the
     *      {@code sourceDirectory}. This is typically a list of jar files. Entries may be relative to
     *      {@code projectDirectory}. 
     * @param testExecutionClassPath The classpath required to execute the test suite. This should include the test
     *      classes themselves, the required runtime dependencies (both for the program and for the test suite), but
     *      <b>not</b> the compiled classes of the program under test. Entries may be relative to
     *      {@code projectDirectory}.
     * @param testClassNames A list of fully qualified names for all test classes.
     * 
     * @throws IllegalArgumentException If consistency checks fail (e.g. project or source directory don't exist).
     */
    public Project(Path projectDirectory, Path sourceDirectory, List<Path> compilationClasspath,
            List<Path> testExecutionClassPath, List<String> testClassNames) throws IllegalArgumentException {
        this.projectDirectory = projectDirectory;
        this.sourceDirectory = sourceDirectory;
        this.compilationClasspath = compilationClasspath;
        this.testExecutionClassPath = testExecutionClassPath;
        this.testClassNames = testClassNames;
        this.encoding = Charset.defaultCharset();
        
        checkConsistency();
    }
    
    private void checkConsistency() throws IllegalArgumentException {
        if (!Files.isDirectory(projectDirectory)) {
            throw new IllegalArgumentException("Project directory " + projectDirectory + " does not exist");
        }
        
        if (!Files.isDirectory(projectDirectory.resolve(sourceDirectory))) {
            missingPath("Source directory", sourceDirectory, true);
        }
        
        for (Path classpathElement : compilationClasspath) {
            checkPathExists("Classpath element", classpathElement);
        }
        
        for (Path classpathElement : testExecutionClassPath) {
            checkPathExists("Classpath element", classpathElement);
        }
        
        if (testClassNames.isEmpty()) {
            throw new IllegalArgumentException("No test class names provided");
        }
        
        String identifier = "[\\p{Alpha}_$][\\p{Alnum}_$]*"; 
        Pattern classNamePattern = Pattern.compile("(" + identifier + "\\.)*" + identifier);
        for (String testClassName : testClassNames) {
            if (!classNamePattern.matcher(testClassName).matches()) {
                throw new IllegalArgumentException("Invalid class name in test class names: " + testClassName);
            }
        }
    }
    
    private void checkPathExists(String nameForError, Path toCheck) throws IllegalArgumentException {
        if (!Files.exists(projectDirectory.resolve(toCheck))) {
            missingPath(nameForError, toCheck, false);
        }
    }

    private void missingPath(String nameForError, Path missingPath, boolean throwException)
            throws IllegalArgumentException {
        
        if (missingPath.isAbsolute()) {
            if (throwException) {
                throw new IllegalArgumentException(nameForError + " " + missingPath + " does not exist");
            } else {
                LOG.warning(() -> nameForError + " " + missingPath + " does not exist");
            }
        } else {
            if (throwException) {
                throw new IllegalArgumentException(nameForError + " " + missingPath
                        + " does not exist in project directory " + projectDirectory);
            } else {
                LOG.warning(() -> nameForError + " " + missingPath + " does not exist in project directory "
                        + projectDirectory);
            }
        }
    }
    
    public static String getCliUsage() {
        return "Usage: --project-directory <projectDirectory> --source-directory <sourceDirectory> "
                + "[--compile-classpath <compilationClasspath>] --test-classpath <testExecutionClassPath> "
                + "--test-classes <testClasses> [--encoding <encoding>]";
    }
    
    public static Project readFromCommandLine(String[] args) throws IllegalArgumentException {
        CliArguments cli = new CliArguments(args, Set.of("--project-directory", "--source-directory",
                "--compile-classpath", "--test-classpath", "--test-classes", "--encoding"));
        
        Path projectDirectory = Path.of(cli.getOptionOrThrow("--project-directory"));
        Path sourceDirectory = Path.of(cli.getOptionOrThrow("--source-directory"));
        List<Path> compilationClasspath = readClasspath(cli.getOption("--compile-classpath", ""));
        List<Path> testExecutionClassPath = readClasspath(cli.getOptionOrThrow("--test-classpath"));
        List<String> testClassNames = Arrays.asList(cli.getOptionOrThrow("--test-classes").split(":"));
        
        if (!cli.getRemaining().isEmpty()) {
            throw new IllegalArgumentException("Too many arguments: " + cli.getRemaining());
        }
        
        Project project = new Project(projectDirectory, sourceDirectory, compilationClasspath, testExecutionClassPath,
                testClassNames);
        
        if (cli.hasOption("--encoding")) {
            project.setEncoding(Charset.forName(cli.getOption("--encoding")));
        }
        
        return project;
    }
    
    private static List<Path> readClasspath(String combined) {
        List<Path> classpath = new LinkedList<>();
        
        if (!combined.isEmpty()) {
            char seperator;
            if (combined.indexOf(':') != -1 && combined.indexOf(';') == -1) {
                seperator = ':';
            } else {
                seperator = File.pathSeparatorChar;
            }
            
            for (String element : combined.split(String.valueOf(seperator))) {
                classpath.add(Path.of(element));
            }
            
        }
        
        return classpath;
    }
    
    public void setEncoding(Charset encoding) {
        this.encoding = encoding;
    }
    
    /**
     * The root directory of the project.
     */
    public Path getProjectDirectory() {
        return projectDirectory;
    }

    /**
     * The base directory where the Java source code files are located. May be relative to the
     * {@link #getProjectDirectory() project directory}.
     */
    public Path getSourceDirectory() {
        return sourceDirectory;
    }
    
    /**
     * Same as {@link #getSourceDirectory()}, but if necessary resolves a relative path against the
     * {@link #getProjectDirectory() project directory}.
     */
    public Path getSourceDirectoryAbsolute() {
        return projectDirectory.resolve(sourceDirectory);
    }

    /**
     * The classpath of dependencies required to compile the classes in the
     * {@link #getSourceDirectory() source directory}. This is typically a list of jar files. Entries may be relative
     * to the {@link #getProjectDirectory() project directory}.
     */
    public List<Path> getCompilationClasspath() {
        return compilationClasspath;
    }
    
    /**
     * Same as {@link #getCompilationClasspath()}, but if necessary resolves any relative paths against the
     * {@link #getProjectDirectory() project directory}.
     */
    public List<Path> getCompilationClasspathAbsolute() {
        return compilationClasspath.stream().map(p -> projectDirectory.resolve(p).toAbsolutePath()).toList();
    }

    /**
     * The classpath required to execute the test suite. This includes the test classes themselves, the required
     * runtime dependencies (both for the program and for the test suite), but <b>not</b> the compiled classes of the
     * program under test. Entries may be relative to the {@link #getProjectDirectory() project directory}.
     */
    public List<Path> getTestExecutionClassPath() {
        return testExecutionClassPath;
    }
    
    /**
     * Same as {@link #getTestExecutionClassPath}, but if necessary resolves any relative paths against the
     * {@link #getProjectDirectory() project directory}.
     */
    public List<Path> getTestExecutionClassPathAbsolute() {
        return testExecutionClassPath.stream().map(p -> projectDirectory.resolve(p).toAbsolutePath()).toList();
    }

    /**
     * A list of fully qualified names for all test classes.
     */
    public List<String> getTestClassNames() {
        return testClassNames;
    }
    
    public Charset getEncoding() {
        return encoding;
    }
    
}
