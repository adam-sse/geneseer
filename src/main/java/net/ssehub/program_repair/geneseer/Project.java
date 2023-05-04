package net.ssehub.program_repair.geneseer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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
        return compilationClasspath.stream().map(p -> projectDirectory.resolve(p)).toList();
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
     * A list of fully qualified names for all test classes.
     */
    public List<String> getTestClassNames() {
        return testClassNames;
    }
    
}
