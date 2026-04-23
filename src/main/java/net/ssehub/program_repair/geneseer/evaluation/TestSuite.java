package net.ssehub.program_repair.geneseer.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class TestSuite {

    private static final Logger LOG = Logger.getLogger(TestSuite.class.getName());
    
    private ProjectCompiler compiler;
    
    private JunitEvaluation junitSuite;
    
    private FaultLocalization faultLocalization;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Node originalSourceCode;
    
    private Map<String, Set<String>> testMethods;
    
    private Map<String, TestResult> initialTestResults;
    
    private int numCompilations;
    
    private int numTestSuiteRuns;
    
    public TestSuite(Project project, Node sourceCode, TemporaryDirectoryManager tempDirManager)
            throws EvaluationException {
        this.tempDirManager = tempDirManager;
        this.compiler = createCompiler(project);
        this.junitSuite = new JunitEvaluation(project.getProjectDirectory(),
                project.getTestExecutionClassPathAbsolute(), project.getEncoding(), project.getSplitTestClassLoaders());
        this.faultLocalization = new FaultLocalization(project.getProjectDirectory(),
                project.getTestExecutionClassPathAbsolute(), project.getEncoding(), project.getSplitTestClassLoaders(),
                tempDirManager);
        this.originalSourceCode = sourceCode;
        
        this.testMethods = new LinkedHashMap<>();
        for (String testClass : project.getTestClassNames()) {
            testMethods.put(testClass, new LinkedHashSet<>());
        }
        this.initialTestResults = new LinkedHashMap<>();
        
        initialize();
    }
    
    private ProjectCompiler createCompiler(Project project) throws CompilationException {
        try {
            Path compilerSourceDirectory = tempDirManager.createTemporaryDirectory();
            Path compilerOutputDirectory = tempDirManager.createTemporaryDirectory();
            FileUtils.copyAllNonJavaSourceFiles(project.getSourceDirectoryAbsolute(), compilerSourceDirectory);
            FileUtils.copyAllNonJavaSourceFiles(project.getSourceDirectoryAbsolute(), compilerOutputDirectory);
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute(),
                    project.getEncoding(), compilerSourceDirectory, compilerOutputDirectory);
            compiler.setAdditionalOptions(project.getAdditionalCompilerOptions());
            return compiler;
        } catch (IOException e) {
            throw new CompilationException("Can't setup directories for compiler", e);
        }
    }
    
    private void initialize() throws EvaluationException {
        LOG.info("Compiling original source code");
        compiler.setLogResultToInfo(true);
        compile(originalSourceCode);
        compiler.setLogResultToInfo(false);
        
        LOG.info("Running test suite on original code");
        List<TestResult> testResult = runTests(compiler.getOutputDirectory(), testMethods.keySet());
        for (TestResult tr : testResult) {
            if (!testMethods.containsKey(tr.testClass())) {
                throw new TestIntegrityException("Got unknown test class as evaluation result: " + tr.testClass());
            }
            if (tr.isTimeout()) {
                throw new TestIntegrityException("Got timeout in original evaluation (in class " + tr.testClass()
                        + ")");
            }
            testMethods.get(tr.testClass()).add(tr.getMethodIdentifier());
            initialTestResults.put(tr.getIdentifier(), tr);
        }
        if (initialTestResults.size() != testResult.size()) {
            List<String> testNames = testResult.stream().map(TestResult::getIdentifier).collect(Collectors.toList());
            for (String name : initialTestResults.keySet()) {
                testNames.remove(name);
            }
            throw new TestExecutionException("Found " + testNames.size() + " duplicate test names in test result: "
                    + testNames);
        }
        LOG.info(() -> "Got " + getInitialPassingTestResults().size() + " passing and "
                + getInitialFailingTestResults().size() + " failing tests");
        
        LOG.info("Running fault localization and annotating original code with suspiciousness");
        faultLocalization.measureAndAnnotateSuspiciousness(originalSourceCode, compiler.getOutputDirectory(),
                testResult);
    }
    
    @SuppressWarnings("unchecked")
    public List<TestResult> evaluate(Node ast) throws EvaluationException {
        compile(ast);
        
        Set<Node> modifiedFiles = computeModifiedFiles(originalSourceCode, ast);
        for (Node modifiedFile : modifiedFiles) {
            if (modifiedFile.getMetadata(Metadata.COVERED_BY) == null) {
                throw new TestIntegrityException("File node is missing coverage information");
            }
        }
        LOG.fine(() -> "Detected " + modifiedFiles.size() + " modified files");
        Set<String> relevantTestClasses = new LinkedHashSet<>();
        modifiedFiles.stream()
                .map(n -> (Set<String>) n.getMetadata(Metadata.COVERED_BY))
                .flatMap(Set::stream)
                .forEach(relevantTestClasses::add);
        LOG.fine(() -> "Only running " + relevantTestClasses.size() + " relevant test classes (out of "
                + testMethods.size() + " total): " + relevantTestClasses);
        
        List<TestResult> testResult = runTests(compiler.getOutputDirectory(), relevantTestClasses);
        List<TestResult> extendedTestResult = new LinkedList<>();
        for (TestResult tr : testResult) {
            Set<String> knownMethods = testMethods.get(tr.testClass());
            if  (knownMethods == null) {
                throw new TestIntegrityException("Unknown test class in result: " + tr.testClass());
            }
            if (tr.isTimeout()) {
                for (String testMethod : knownMethods) {
                    extendedTestResult.add(new TestResult(tr.testClass(), testMethod, tr.implementingClass(),
                            "Timeout", "Timeout"));
                }
            } else {
                if (!knownMethods.contains(tr.getMethodIdentifier())) {
                    throw new TestIntegrityException("Unknown test method in result: " + tr.getIdentifier());
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
    }
    
    public List<TestResult> runAndAnnotateFaultLocalization(Node ast) throws EvaluationException {
        compile(ast);
        
        List<TestResult> testResult = runTests(compiler.getOutputDirectory(), testMethods.keySet());
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
        
        faultLocalization.measureAndAnnotateSuspiciousness(ast, compiler.getOutputDirectory(), testResult);
        
        return testResult;
    }
    
    private void compile(Node ast) throws CompilationException {
        numCompilations++;
        compiler.compile(ast);
    }
    
    private List<TestResult> runTests(Path binDirectory, Set<String> testClassNames) throws TestExecutionException {
        numTestSuiteRuns++;
        return junitSuite.runTests(binDirectory, testClassNames);
    }
    
    private static Map<Path, Node> getFileNodesByPath(Node astRoot)  throws EvaluationException {
        Map<Path, Node> fileNodes = new LinkedHashMap<>();
        
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
        
        Set<Node> modifiedFiles = new LinkedHashSet<>();
        for (Path file : newAstFiles.keySet()) {
            Node newFile = newAstFiles.get(file);
            Node oldFile = oldAstFiles.get(file);
            if (!oldFile.contentEquals(newFile)) {
                modifiedFiles.add(newFile);
            }
        }
        
        return modifiedFiles;
    }
    
    public List<TestResult> getInitialTestResults() {
        return Collections.unmodifiableList(new ArrayList<>(initialTestResults.values()));
    }
    
    public List<TestResult> getInitialPassingTestResults() {
        return initialTestResults.values().stream()
                .filter(t -> !t.isFailure())
                .collect(Collectors.toUnmodifiableList());
    }
    
    public List<TestResult> getInitialFailingTestResults() {
        return initialTestResults.values().stream()
                .filter(TestResult::isFailure)
                .collect(Collectors.toUnmodifiableList());
    }
    
    public int getNumCompilations() {
        return numCompilations;
    }

    public int getNumTestSuiteRuns() {
        return numTestSuiteRuns;
    }
    
}
