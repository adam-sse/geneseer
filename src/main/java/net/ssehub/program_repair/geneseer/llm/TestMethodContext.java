package net.ssehub.program_repair.geneseer.llm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.code.Parser;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;

public record TestMethodContext(TestResult testResult, String code, Path file) {
    
    private static final Logger LOG = Logger.getLogger(TestMethodContext.class.getName());
    
    public static List<TestMethodContext> constructContext(Collection<TestResult> failingTests, Path projectRoot,
            Charset encoding) {
        return failingTests.stream()
                .map(tr -> constructTestMethodContext(tr, projectRoot, encoding))
                .toList();
    }

    public static TestMethodContext constructTestMethodContext(TestResult failingTest, Path projectRoot,
            Charset encoding) {
        if (!failingTest.isFailure()) {
            throw new IllegalArgumentException("Cannot get test method context for non-failing test: " + failingTest);
        }
        
        TestLocation location = findTestLocationInStacktrace(failingTest);
        TestMethodContext result;
        try {
            List<Path> testFiles = Files.walk(projectRoot)
                    .filter(p -> p.getFileName().toString().equals(location.filename()))
                    .filter(p -> Files.isRegularFile(p))
                    .toList();
            
            List<TestMethodContext> found = new LinkedList<>();
            for (Path testFile : testFiles) {
                found.addAll(findTestMethodInFile(failingTest, location, testFile, projectRoot, encoding));
            }
            
            if (found.size() == 1) {
                result = found.get(0);
            } else if (found.size() > 1) {
                int pathMatchesClassName = -1;
                Path expected = Path.of(failingTest.testClass().replace('.', File.separatorChar) + ".java");
                for (int i = 0; i < found.size(); i++) {
                    if (found.get(i).file().endsWith(expected)) {
                        if (pathMatchesClassName == -1) {
                            pathMatchesClassName = i;
                        } else {
                            // two found paths match...
                            pathMatchesClassName = -1;
                            break;
                        }
                    }
                }
                
                if (pathMatchesClassName != -1) {
                    result = found.get(pathMatchesClassName);
                } else {
                    LOG.warning(() -> "Found " + found.size() + " tests at " + location + " for test "
                            + failingTest);
                    result = new TestMethodContext(failingTest, null, null);
                }
                
            } else {
                if (testFiles.isEmpty()) {
                    LOG.warning(() -> "Could not find test file at " + location + " for test " + failingTest);
                } else {
                    LOG.warning(() -> "Could not find test method at " + location + " in any of these test files "
                            + testFiles + " for test " + failingTest);
                }
                result = new TestMethodContext(failingTest, null, null);
            }
                
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read test method file", e);
            result = new TestMethodContext(failingTest, null, null);
        }
        return result;
    }
    
    private record TestLocation(String filename, String methodName, int lineNumber) {
     
        @Override
        public final String toString() {
            return filename + ":" + lineNumber + " (method: " + methodName + ")";
        }
        
    }
    
    private static TestLocation findTestLocationInStacktrace(TestResult testResult) {
        Pattern regex = Pattern.compile(Pattern.quote("." + testResult.testMethod())
                + "\\((?<filename>.+\\.java):(?<lineNumber>[0-9]+)\\)");
        Matcher m = regex.matcher(testResult.failureStacktrace());
        TestLocation result;
        if (m.find()) {
            result = new TestLocation(m.group("filename"), testResult.testMethod(),
                    Integer.parseInt(m.group("lineNumber")));
        } else {
            String testFileName = testResult.testClass()
                    .substring(testResult.testClass().lastIndexOf('.') + 1) + ".java";
            result = new TestLocation(testFileName, testResult.testMethod(), -1);
            LOG.fine(() -> "Could not find location of test " + testResult + " in stacktrace:\n"
                    + testResult.failureStacktrace() + " -> guessing: " + result);
        }
        
        return result;
    }
    
    private static List<TestMethodContext> findTestMethodInFile(TestResult failingTest, TestLocation location,
            Path testFile, Path projectRoot, Charset encoding) throws IOException {
        
        Node file = new Parser().parseSingleFile(testFile, encoding);
        Stream<Node> stream = file.stream()
                .filter(n -> n.getType() == Type.METHOD)
                .filter(n -> n.getMetadata(Metadata.METHOD_NAME).equals(location.methodName()));
        if (location.lineNumber() != -1) {
            stream = stream.filter(n -> {
                int lineStart = AstUtils.getLine(file, n);
                int lineEnd = lineStart + AstUtils.getAdditionalLineCount(n);
                return location.lineNumber() >= lineStart && location.lineNumber() <= lineEnd;
            });
        }
        List<Node> methods = stream.toList();

        if (methods.size() != 1) {
            LOG.fine(() -> "Found " + methods.size() + " possible test code candidates at " + location
                    + " in file " + testFile + " for test " + failingTest);
        }
        return methods.stream()
                .map(n -> new TestMethodContext(failingTest, n.getTextFormatted(), projectRoot.relativize(testFile)))
                .toList();
    }
    
}
