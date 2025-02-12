package net.ssehub.program_repair.geneseer.evaluation;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class TestSuite {

    private static final Logger LOG = Logger.getLogger(TestSuite.class.getName());
    
    private ProjectCompiler compiler;
    
    private JunitEvaluation junitSuite;
    
    private FaultLocalization faultLocalization;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Charset encoding;
    
    private Path originalSourceDirectory;
    
    private Node originalSourceCode;
    
    private Map<String, Set<String>> testMethods;
    
    private Map<String, TestResult> initialTestResults;
    
    private int numCompilations;
    
    private int numTestSuiteRuns;
    
    public TestSuite(Project project, Node sourceCode, TemporaryDirectoryManager tempDirManager)
            throws EvaluationException {
        this.compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute(), project.getEncoding());
        this.junitSuite = new JunitEvaluation(project.getProjectDirectory(),
                project.getTestExecutionClassPathAbsolute(), project.getEncoding());
        this.faultLocalization = new FaultLocalization(project.getProjectDirectory(),
                project.getTestExecutionClassPathAbsolute(), project.getEncoding());
        this.encoding = project.getEncoding();
        this.originalSourceDirectory = project.getSourceDirectoryAbsolute();
        this.originalSourceCode = sourceCode;
        this.tempDirManager = tempDirManager;
        
        this.testMethods = new HashMap<>();
        for (String testClass : project.getTestClassNames()) {
            testMethods.put(testClass, new HashSet<>());
        }
        this.initialTestResults = new HashMap<>();
        
        initialize();
    }
    
    private void initialize() throws EvaluationException {
        LOG.info("Compiling original source code");
        compiler.setLogOutput(true);
        Path binDir = compile(originalSourceCode);
        compiler.setLogOutput(false);
        
        try {
            LOG.info("Running test suite on original code");
            List<TestResult> testResult = runTests(binDir, testMethods.keySet());
            for (TestResult tr : testResult) {
                if (!testMethods.containsKey(tr.testClass())) {
                    throw new TestIntegrityException("Got unknown test class as evaluation result: " + tr.testClass());
                }
                if (tr.isTimeout()) {
                    throw new TestIntegrityException("Got timeout in original evalution (in class " + tr.testClass()
                            + ")");
                }
                testMethods.get(tr.testClass()).add(tr.testMethod());
                initialTestResults.put(tr.toString(), tr);
            }
            if (initialTestResults.size() != testResult.size()) {
                throw new TestIntegrityException("Got duplicate test names in test suite result");
            }
            LOG.info(() -> "Got " + getInitialPassingTestResults().size() + " passing and "
                    + getInitialFailingTestResults().size() + " failing tests");
            
            LOG.info("Running fault localization and annotating original code with suspiciousness");
            faultLocalization.measureAndAnnotateSuspiciousness(originalSourceCode, binDir, testResult);
            
        } finally {
            deleteTempDirNoEx(binDir);
        }
    }
    
    public List<TestResult> evaluate(Node ast) throws EvaluationException {
        Path binDir = compile(ast);
        try {
            Set<Node> modifiedFiles = computeModifiedFiles(originalSourceCode, ast);
            for (Node modifiedFile : modifiedFiles) {
                if (modifiedFile.getMetadata(Metadata.COVERED_BY) == null) {
                    throw new TestIntegrityException("File node is missing coverage information");
                }
            }
            LOG.fine(() -> "Detected " + modifiedFiles.size() + " modified files");
            @SuppressWarnings("unchecked")
            Set<String> relevantTestClasses = modifiedFiles.stream()
                    .map(n -> (Set<String>) n.getMetadata(Metadata.COVERED_BY))
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet());
            LOG.fine(() -> "Only running " + relevantTestClasses.size() + " relevant test classes (out of "
                    + testMethods.size() + " total): " + relevantTestClasses);
            
            List<TestResult> testResult = runTests(binDir, relevantTestClasses);
            List<TestResult> extendedTestResult = new LinkedList<>();
            for (TestResult tr : testResult) {
                Set<String> knownMethods = testMethods.get(tr.testClass());
                if  (knownMethods == null) {
                    throw new TestIntegrityException("Unknown test class in result: " + tr.testClass());
                }
                if (tr.isTimeout()) {
                    for (String testMethod : knownMethods) {
                        extendedTestResult.add(new TestResult(tr.testClass(), testMethod, "Timeout", "Timeout"));
                    }
                } else {
                    if (!knownMethods.contains(tr.testMethod())) {
                        throw new TestIntegrityException("Unknown test method in result: " + tr.toString());
                    }
                    extendedTestResult.add(tr);
                }
            }
            
            for (Map.Entry<String, Set<String>> entry : testMethods.entrySet()) {
                if (!relevantTestClasses.contains(entry.getKey())) {
                    for (String testMethod : entry.getValue()) {
                        String testName = entry.getKey() + "::" + testMethod;
                        TestResult initialResult = initialTestResults.get(testName);
                        if (initialResult == null) {
                            throw new TestIntegrityException("Can't find initial result for test " + testName);
                        }
                        extendedTestResult.add(initialResult);
                    }
                }
            }
            
            return extendedTestResult;
            
        } finally {
            deleteTempDirNoEx(binDir);
        }
    }
    
    public List<TestResult> runAndAnnotateFaultLocalization(Node ast) throws EvaluationException {
        Path binDir = compile(ast);
        
        try {
            List<TestResult> testResult = runTests(binDir, testMethods.keySet());
            for (TestResult tr : testResult) {
                Set<String> knownMethods = testMethods.get(tr.testClass());
                if  (knownMethods == null) {
                    throw new TestIntegrityException("Unknown test class in result: " + tr.testClass());
                }
                if (tr.isTimeout()) {
                    throw new TestIntegrityException("Got timeout when trying to run fault localization");
                }
            }
            if (initialTestResults.size() != testResult.size()) {
                throw new TestIntegrityException("Got wrong number of test results: " + testResult.size()
                        + " (expected " + initialTestResults.size() + ")");
            }
            
            faultLocalization.measureAndAnnotateSuspiciousness(ast, binDir, testResult);
            
            return testResult;
            
        } finally {
            deleteTempDirNoEx(binDir);
        }
    }
    
    private Path compile(Node ast) throws CompilationException {
        numCompilations++;
        
        Path sourceDirectory = null;
        Path binDirectory = null;
        try {
            sourceDirectory = tempDirManager.createTemporaryDirectory();
            binDirectory = tempDirManager.createTemporaryDirectory();
            Writer.write(ast, originalSourceDirectory, sourceDirectory, encoding);
            
            compiler.compile(sourceDirectory, binDirectory);
            return binDirectory;
            
        } catch (IOException e) {
            deleteTempDirNoEx(binDirectory);
            throw new CompilationException("Failed to write code", e);
            
        } finally {
            // always clean up source directory
            deleteTempDirNoEx(sourceDirectory);
        }
    }
    
    private List<TestResult> runTests(Path binDirectory, Set<String> testClassNames) throws TestExecutionException {
        numTestSuiteRuns++;
        return junitSuite.runTests(binDirectory, testClassNames);
    }
    
    private static Map<Path, Node> getFileNodesByPath(Node astRoot)  throws EvaluationException {
        Map<Path, Node> fileNodes = new HashMap<>();
        
        for (Node child : astRoot.childIterator()) {
            Path path = (Path) child.getMetadata(Metadata.FILE_NAME);
            if (path == null) {
                throw new TestIntegrityException("File node is missing FILE_NAME metadata");
            }
            fileNodes.put(path, child);
        }
        
        return fileNodes;
    }
    
    private static Set<Node> computeModifiedFiles(Node oldAst, Node newAst) throws EvaluationException {
        Map<Path, Node> oldAstFiles = getFileNodesByPath(oldAst);
        Map<Path, Node> newAstFiles = getFileNodesByPath(newAst);
        
        if (!oldAstFiles.keySet().equals(newAstFiles.keySet())) {
            throw new TestIntegrityException("Files have changed between old and new AST");
        }
        
        Set<Node> modifiedFiles = new HashSet<>();
        for (Path file : newAstFiles.keySet()) {
            Node newFile = newAstFiles.get(file);
            Node oldFile = oldAstFiles.get(file);
            if (!oldFile.equals(newFile)) {
                modifiedFiles.add(newFile);
            }
        }
        
        return modifiedFiles;
    }
    
    private void deleteTempDirNoEx(Path directory) {
        if (directory != null) {
            try {
                tempDirManager.deleteTemporaryDirectory(directory);
            } catch (IOException e) {
                // ignore, will be cleaned up alter
            }
        }
    }
    
    public Set<TestResult> getInitialTestResults() {
        return Collections.unmodifiableSet(new HashSet<>(initialTestResults.values()));
    }
    
    public Set<TestResult> getInitialPassingTestResults() {
        return initialTestResults.values().stream()
                .filter(t -> !t.isFailure())
                .collect(Collectors.toUnmodifiableSet());
    }
    
    public Set<TestResult> getInitialFailingTestResults() {
        return initialTestResults.values().stream()
                .filter(TestResult::isFailure)
                .collect(Collectors.toUnmodifiableSet());
    }
    
    public int getNumCompilations() {
        return numCompilations;
    }

    public int getNumTestSuiteRuns() {
        return numTestSuiteRuns;
    }
    
}
