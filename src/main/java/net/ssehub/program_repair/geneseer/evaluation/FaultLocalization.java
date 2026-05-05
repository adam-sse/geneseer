package net.ssehub.program_repair.geneseer.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.evaluation.TestExecution.TestResultWithCoverage;
import net.ssehub.program_repair.geneseer.util.AstLocations;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

class FaultLocalization {
    
    private static final Logger LOG = Logger.getLogger(FaultLocalization.class.getName());

    private Path workingDirectory;
    
    private List<Path> classpath;
    
    private Charset encoding;
    
    private boolean splitTestClassLoaders;
    
    private TemporaryDirectoryManager tempDirManager;
    
    public FaultLocalization(Path workingDirectory, List<Path> classpath, Charset encoding,
            boolean splitTestClassLoaders, TemporaryDirectoryManager tempDirManager) {
        this.workingDirectory = workingDirectory;
        this.classpath = classpath;
        this.encoding = encoding;
        this.splitTestClassLoaders = splitTestClassLoaders;
        this.tempDirManager = tempDirManager;
    }
    
    public void measureAndAnnotateSuspiciousness(Node ast, Path variantBinDir, List<TestResult> tests)
            throws TestExecutionException {
        LOG.info("Measuring suspiciousness");
        LinkedHashMap<Location, Double> suspiciousness = measureSuspiciousness(tests, variantBinDir, ast);
        
        Map<String, Node> fileNodesByClassName = getFileNodesByClassName(ast);
        Map<Node, AstLocations> locations = new HashMap<>(fileNodesByClassName.size());
        for (Node file : fileNodesByClassName.values()) {
            locations.put(file, new AstLocations(file));
        }
        
        for (Map.Entry<Location, Double> entry : suspiciousness.entrySet()) {
            int line = entry.getKey().line();
            double susValue = entry.getValue();
            
            Node fileNode = findFileNode(entry.getKey().className(), fileNodesByClassName);
            if (fileNode != null) {
                String fileName = fileNode.getMetadata(Metadata.FILE_NAME).toString();
                List<Node> matchingStatements = fileNode.stream()
                        .filter(n -> n.getType() == Type.STATEMENT)
                        .filter(n -> locations.get(fileNode).getStatementsAtLine(line).contains(n))
                        .collect(Collectors.toList());
                
                if (matchingStatements.isEmpty()) {
                    // these are usually implicit returns at the end of void methods, at the line of the closing }
                    LOG.fine(() -> "Found no statements for suspicious " + susValue + " at " + fileName + ":" + line);
                } else if (matchingStatements.size() > 1) {
                    removeParentsOfLastElement(matchingStatements, ast);
                    if (matchingStatements.size() > 1) {
                        LOG.fine(() -> "Found " + matchingStatements.size() + " statements for " + fileName
                                + ":" + line + "; adding suspiciousness to all of them");
                    }
                }
                
                for (Node stmt : matchingStatements) {
                    if (stmt.getMetadata(Metadata.SUSPICIOUSNESS) == null
                            || ((double) stmt.getMetadata(Metadata.SUSPICIOUSNESS)) < susValue) {
                        LOG.fine(() -> "Suspicious " + susValue + " at " + fileName + ":" + line
                                + " '" + stmt.getTextSingleLine() + "'");
                        stmt.setMetadata(Metadata.SUSPICIOUSNESS, susValue);
                    }
                }
            } else {
                LOG.warning(() -> "Can't find class in AST: " + entry.getKey().className());
            }
        }
        removeBelowThreshold(ast, Configuration.INSTANCE.setup().suspiciousnessThreshold());
        removeToKeepLimit(ast, Configuration.INSTANCE.setup().suspiciousStatementLimit());
        long suspiciousStatementCount = ast.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .count();
        LOG.log(suspiciousStatementCount > 0 ? Level.INFO : Level.WARNING,
                () -> suspiciousStatementCount + " suspicious statements");
    }
    
    private static Node findFileNode(String classNameFromSuspiciousness, Map<String, Node> classes) {
        Node result = classes.get(classNameFromSuspiciousness);
        int dollarIndex;
        while (result == null && (dollarIndex = classNameFromSuspiciousness.lastIndexOf('$')) != -1) {
            classNameFromSuspiciousness = classNameFromSuspiciousness.substring(0, dollarIndex);
            result = classes.get(classNameFromSuspiciousness);
        }
        return result;
    }

    private static void removeBelowThreshold(Node ast, double threshold) {
        int count = (int) ast.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .filter(n -> ((double) n.getMetadata(Metadata.SUSPICIOUSNESS)) < threshold)
                .peek(n -> n.setMetadata(Metadata.SUSPICIOUSNESS, null))
                .count();
        if (count > 0) {
            LOG.info(() -> "Removed " + count + " suspicious statements below suspiciousness threshold");
        }
    }
    
    private static void removeToKeepLimit(Node ast, int limit) {
        double[] highestSuspiciousnessValue = {-1};
        int count = (int) ast.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .sorted(Node.DESCENDING_SUSPICIOUSNESS)
                .skip(limit)
                .peek(n -> {
                    if (highestSuspiciousnessValue[0] <= 0) {
                        highestSuspiciousnessValue[0] = (double) n.getMetadata(Metadata.SUSPICIOUSNESS);
                    }
                    n.setMetadata(Metadata.SUSPICIOUSNESS, null);
                })
                .count();
        if (count > 0) {
            LOG.info(() -> "Removed " + count + " suspicious statements to keep limit, cutoff at suspiciousness "
                    + highestSuspiciousnessValue[0]);
        }
    }

    private static Map<String, Node> getFileNodesByClassName(Node ast) {
        Map<String, Node> fileNodes = new HashMap<>(ast.childCount());
        for (Node file : ast.childIterator()) {
            file.stream()
                    .filter(n -> n.getMetadata(Metadata.TYPE_NAME) != null)
                    .forEach(n -> fileNodes.put((String) n.getMetadata(Metadata.TYPE_NAME), file));
        }
        return fileNodes;
    }
    
    private static void removeParentsOfLastElement(List<Node> nodes, Node root) {
        Node last = nodes.get(nodes.size() - 1);
        List<Node> parentsToRemove = root.getPath(last);
        
        for (int i = 0; i < nodes.size() - 1; i++) {
            Node possibleParent = nodes.get(i);
            for (Node parent : parentsToRemove) {
                if (possibleParent == parent) {
                    nodes.remove(i);
                    i--;
                    break;
                }
            }
        }
    }
    
    private LinkedHashMap<Location, Double> measureSuspiciousness(List<TestResult> tests, Path classesDirectory,
            Node ast) throws TestExecutionException {
        
        try (Probe probe = Measurement.INSTANCE.start("fault-localization")) {
            Map<Location, Set<TestResult>> coverage = measureCoverage(tests, classesDirectory);
            annotateTestCoverageOnFileAndMethodNodes(coverage, ast, tests);
            
            Map<Location, Double> suspiciousness = new HashMap<>(coverage.size());
            for (Map.Entry<Location, Set<TestResult>> coverageEntry : coverage.entrySet()) {
                int nPassingNotExecuting = 0;
                int nFailingNotExecuting = 0;
                int nPassingExecuting = 0;
                int nFailingExecuting = 0;
                
                for (TestResult test : tests) {
                    boolean testCoversLine = coverageEntry.getValue().contains(test);
                    
                    if (testCoversLine) {
                        if (test.isFailure()) {
                            nFailingExecuting++;
                        } else {
                            nPassingExecuting++;
                        }
                    } else {
                        if (test.isFailure()) {
                            nFailingNotExecuting++;
                        } else {
                            nPassingNotExecuting++;
                        }
                    }
                }
                
                double susValue = ochiaiFormula(nPassingNotExecuting, nFailingNotExecuting,
                        nPassingExecuting, nFailingExecuting);
                if (susValue > 0) {
                    suspiciousness.put(coverageEntry.getKey(), susValue);
                }
            }
            
            LinkedHashMap<Location, Double> sortedSuspiciousness = new LinkedHashMap<>(suspiciousness.size());
            suspiciousness.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                    .forEach(e -> sortedSuspiciousness.put(e.getKey(), e.getValue()));
            return sortedSuspiciousness;
        }
    }
    
    private static void annotateTestCoverageOnFileAndMethodNodes(Map<Location, Set<TestResult>> coverage, Node ast,
            List<TestResult> allTests) {
        
        Map<String, Node> filesByClassName = getFileNodesByClassName(ast);
        initializeEmptyCoveredBy(ast, filesByClassName.values(), allTests);
        
        Map<Node, List<Map.Entry<Location, Set<TestResult>>>> coverageByFile
                = new HashMap<>(coverage.size());
        for (Map.Entry<Location, Set<TestResult>> entry : coverage.entrySet()) {
            Node fileNode = findFileNode(entry.getKey().className(), filesByClassName);
            if (fileNode != null) {
                List<Map.Entry<Location, Set<TestResult>>> locationsInFile = coverageByFile.get(fileNode);
                if (locationsInFile == null) {
                    locationsInFile = new LinkedList<>();
                    coverageByFile.put(fileNode, locationsInFile);
                }
                locationsInFile.add(entry);
            }
        }
        
        for (Map.Entry<Node, List<Map.Entry<Location, Set<TestResult>>>> entry : coverageByFile.entrySet()) {
            Node fileNode = entry.getKey();
            AstLocations locations = new AstLocations(fileNode);
            
            for (Map.Entry<Location, Set<TestResult>> coverageLocation : entry.getValue()) {
                Location location = coverageLocation.getKey();
                Set<TestResult> tests = coverageLocation.getValue();
                
                @SuppressWarnings("unchecked")
                Set<String> fileCoveredBy = (Set<String>) fileNode.getMetadata(Metadata.COVERED_BY);
                for (TestResult testResult : tests) {
                    fileCoveredBy.add(testResult.testClass());
                }
                
                for (Node method : locations.getMethodsAtLine(location.line())) {
                    @SuppressWarnings("unchecked")
                    Set<String> methodCoveredBy = (Set<String>) method.getMetadata(Metadata.COVERED_BY);
                    for (TestResult testResult : tests) {
                        methodCoveredBy.add(testResult.getIdentifier());
                    }
                }
            }
        }
    }

    private static void initializeEmptyCoveredBy(Node ast, Collection<Node> fileNodes, List<TestResult> allTests) {
        int numTestClasses = allTests.stream()
                .map(TestResult::testClass)
                .collect(Collectors.toSet())
                .size();
        
        for (Node fileNode : fileNodes) {
            if (fileNode.getMetadata(Metadata.COVERED_BY) == null) {
                fileNode.setMetadata(Metadata.COVERED_BY, new HashSet<>(numTestClasses));
            }
        }
        
        ast.stream()
                .filter(n -> n.getType() == Type.METHOD || n.getType() == Type.CONSTRUCTOR)
                .filter(n -> n.getMetadata(Metadata.COVERED_BY) == null)
                .forEach(n -> n.setMetadata(Metadata.COVERED_BY, new HashSet<>(allTests.size())));
    }
    
    private Map<Location, Set<TestResult>> measureCoverage(List<TestResult> tests, Path classesDirectory)
            throws TestExecutionException {
        
        Path instrumentedClassesDirectory = offlineInstrumentClasses(classesDirectory);
        
        List<Path> classpath = new ArrayList<>(this.classpath.size() + 1);
        classpath.add(instrumentedClassesDirectory);
        classpath.addAll(this.classpath);
        
        Map<String, List<TestResult>> testsByClass = new LinkedHashMap<>();
        for (TestResult test : tests) {
            testsByClass
                .computeIfAbsent(test.testClass(), key -> new LinkedList<>())
                .add(test);
        }
        LOG.info(() -> "Running coverage on " + tests.size() + " test methods (in " + testsByClass.size()
                + " classes)");
        
        Map<Location, Set<TestResult>> coverage = new HashMap<>();
        try (TestExecution testExec = new TestExecution(workingDirectory, classpath, encoding, true,
                splitTestClassLoaders)) {
            testExec.setTimeout(Configuration.INSTANCE.setup().testExecutionTimeoutMs());
            
            CoverageParserThread parserThread = new CoverageParserThread(classesDirectory, coverage);
            parserThread.start();
            
            for (Map.Entry<String, List<TestResult>> entry : testsByClass.entrySet()) {
                measureCoverageForClass(entry.getKey(), entry.getValue(), testExec, parserThread);
            }
            
            parserThread.finish();
            ProcessRunner.untilNoInterruptedException(() -> {
                parserThread.join();
                return null;
            });
            
            if (parserThread.exception != null) {
                throw parserThread.exception;
            }
            
        } finally {
            try {
                tempDirManager.deleteTemporaryDirectory(instrumentedClassesDirectory);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to delete temporary directory", e);
            }
        }
        
        return coverage;
    }
    
    private Path offlineInstrumentClasses(Path classesDirectory) throws TestExecutionException {
        try {
            Path instrumentedDirectory = tempDirManager.createTemporaryDirectory();
            Instrumenter instrumenter = new Instrumenter(new OfflineInstrumentationAccessGenerator());
            Files.walkFileTree(classesDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relative = classesDirectory.relativize(dir);
                    Path targetDir = instrumentedDirectory.resolve(relative);
                    Files.createDirectories(targetDir);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relative = classesDirectory.relativize(file);
                    Path target = instrumentedDirectory.resolve(relative);
                    if (file.getFileName().toString().endsWith(".class")) {
                        try (InputStream in = Files.newInputStream(file);
                                OutputStream out = Files.newOutputStream(target)) {
                            instrumenter.instrumentAll(in, out, file.toString());
                        }
                    } else {
                        Files.copy(file, target);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return instrumentedDirectory;
        } catch (IOException e) {
            throw new TestCoverageException("Failed to instrument classes", e);
        }
    }
    
    private static class CoverageParserThread extends Thread {
        
        private static final WorkPackage END_OF_WORK = new WorkPackage(null, null);
        
        private BlockingQueue<WorkPackage> queue = new LinkedBlockingQueue<>();
        
        private Path classesDirectory;
        
        private Map<Location, Set<TestResult>> coverageResult;
        
        private volatile TestCoverageException exception;
        
        public CoverageParserThread(Path classesDirectory, Map<Location, Set<TestResult>> coverageResult) {
            this.classesDirectory = classesDirectory;
            this.coverageResult = coverageResult;
            setDaemon(true);
            setName("CoverageParsing");
        }
        
        private record WorkPackage(TestResult test, ExecutionDataStore executionData) {
        }
        
        public void add(WorkPackage task) {
            queue.add(task);
        }
        
        public void finish() {
            queue.add(END_OF_WORK);
        }

        @Override
        public void run() {
            try {
                WorkPackage task;
                while ((task = ProcessRunner.untilNoInterruptedException(() -> queue.take())) != END_OF_WORK) {
                    parseJacocoCoverage(task.test(), task.executionData());
                }
            } catch (TestCoverageException e) {
                this.exception = e;
            }
        }
        
        private void parseJacocoCoverage(TestResult test, ExecutionDataStore executionData)
                throws TestCoverageException {
        
            final CoverageBuilder coverageBuilder = new CoverageBuilder();
            final Analyzer analyzer = new Analyzer(executionData, coverageBuilder);
            
            try {
                analyzer.analyzeAll(classesDirectory.toFile());
            } catch (IOException e) {
                throw new TestCoverageException("Failed to parse jacoco data", e);
            }
        
            int numCoveredLines = 0;
            for (IClassCoverage classCoverage : coverageBuilder.getClasses()) {
        
                String className = classCoverage.getName().replace('/', '.');
                for (IMethodCoverage methodCoverage : classCoverage.getMethods()) {
                    for (int line = methodCoverage.getFirstLine(); line <= methodCoverage.getLastLine() + 1; line++) {
                        int coveredI = methodCoverage.getLine(line).getInstructionCounter().getCoveredCount();
                        if (coveredI > 0) {
                            numCoveredLines++;
                            coverageResult.compute(new Location(className, line), (k, v) -> {
                                if (v == null) {
                                    v = new HashSet<>();
                                }
                                v.add(test);
                                return v;
                            });
                        }
                    }
                }
            }
            
            int n = numCoveredLines;
            LOG.finer(() -> test + " covered " + n + " lines");
        }
        
    }
    
    private static void measureCoverageForClass(String className, List<TestResult> tests,
            TestExecution testExec, CoverageParserThread parserThread) throws TestExecutionException {
        
        List<TestResultWithCoverage> coverageResults;
        try {
            coverageResults = testExec.executeTestClassWithCoverage(className);
        } catch (TestTimeoutException e) {
            throw new TestExecutionException("Test class " + className + " timed out when run with coverage");
        }
        Set<String> originalTests = getTestNames(tests);
        Set<String> coverageTests = getTestNames(coverageResults.stream()
                .map(TestResultWithCoverage::getTestResult)
                .toList());
        if (!coverageTests.equals(originalTests)) {
            throw new TestExecutionException("Got different test methods in class " + className
                    + " when running with coverage (got " + coverageTests + ", expected " + originalTests + ")");
        }
        
        for (TestResultWithCoverage coverageResult : coverageResults) {
            TestResult actual = coverageResult.getTestResult();
            TestResult expected = tests.stream()
                    .filter(t -> t.testClass().equals(actual.testClass()))
                    .filter(t -> t.getMethodIdentifier().equals(actual.getMethodIdentifier()))
                    .findAny()
                    .orElseThrow(() -> new TestExecutionException("Test returned by coverage run ("
                            + coverageResult.getTestResult() + ") is unknown"));
            if (expected.isFailure() != actual.isFailure()) {
                LOG.severe(() -> "Test result for " + actual + " differs when run with coverage"
                        + "\nWithout coverage:\n" + (expected.isFailure() ? expected.failureStacktrace() : "no failure")
                        + "\nWith coverage:\n" + (actual.isFailure() ? actual.failureStacktrace() : "no failure"));
                throw new TestExecutionException("Test result for " + actual + " differs when run with coverage");
            }
            parserThread.add(new CoverageParserThread.WorkPackage(expected, coverageResult.getCoverage()));
        }
    }
    
    private static Set<String> getTestNames(List<TestResult> tests) throws TestExecutionException {
        List<String> names = tests.stream()
                .map(TestResult::getIdentifier)
                .collect(Collectors.toList());
        Set<String> asSet = new LinkedHashSet<>(names);
        if (asSet.size() != names.size()) {
            for (String name : asSet) {
                names.remove(name);
            }
            throw new TestExecutionException("Found " + names.size() + " duplicate test names in test result: "
                    + names);
        }
        return asSet;
    }
    
    // taken from flacoco
    private static double ochiaiFormula(int nPassingNotExecuting, int nFailingNotExecuting, int nPassingExecuting,
            int nFailingExecuting) {
        double result;
        if ((nFailingExecuting + nPassingExecuting == 0) || (nFailingExecuting + nFailingNotExecuting == 0)) {
            result = 0;
        } else {
            result = nFailingExecuting / Math.sqrt(
                    (nFailingExecuting + nFailingNotExecuting) * (nFailingExecuting + nPassingExecuting));
        }
        return result;
    }
    
}
