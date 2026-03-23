package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.code.Parser;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;

public record TestMethodContext(String code, String testClassName) {
    
    private static final Logger LOG = Logger.getLogger(TestMethodContext.class.getName());
    
    public static List<TestMethodContext> constructContext(Collection<TestResult> testResults, Path projectRoot,
            Charset encoding) {
        return testResults.stream()
                .map(tr -> constructTestMethodContext(tr, projectRoot, encoding))
                .filter(Objects::nonNull)
                .toList();
    }

    public static TestMethodContext constructTestMethodContext(TestResult testResult, Path projectRoot,
            Charset encoding) {
        
        String testFileName = testResult.testClass().substring(testResult.testClass().lastIndexOf('.') + 1) + ".java";
        
        TestMethodContext result = null;
        try {
            List<Path> testFiles = Files.walk(projectRoot)
                    .filter(p -> p.getFileName().toString().equals(testFileName))
                    .filter(p -> Files.isRegularFile(p))
                    .toList();
            
            List<TestMethodContext> found = new LinkedList<>();
            for (Path testFile : testFiles) {
                TestMethodContext ctx = findTestMethodInFile(testResult, testFile, encoding);
                if (ctx != null) {
                    found.add(ctx);
                }
            }
            
            if (found.size() == 1) {
                result = found.get(0);
            } else {
                if (testFiles.isEmpty()) {
                    LOG.warning(() -> "Could not find test file for test class " + testResult.testClass());
                } else if (found.isEmpty()) {
                    LOG.warning(() -> "Could not find test method " + testResult.testMethod() + " in any of these"
                            + " test files: " + testFiles);
                } else {
                    LOG.warning(() -> "Found test method " + testResult.testMethod() + " more than once");
                }
            }
                
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read test method file", e);
        }
        return result;
    }
    
    private static TestMethodContext findTestMethodInFile(TestResult failingTest, Path testFile, Charset encoding)
            throws IOException {
        
        Node file = new Parser().parseSingleFile(testFile, encoding);
        Optional<Node> method = file.stream()
                .filter(n -> n.getType() == Type.METHOD)
                .filter(n -> n.getMetadata(Metadata.METHOD_NAME).equals(failingTest.testMethod()))
                .findAny();
        
        TestMethodContext result = null;
        if (method.isPresent()) {
            String code = method.get().getTextFormatted();
            List<Node> path = file.getPath(method.get());
            String testClassName = null;
            for (int i = path.size() - 1; i >= 0; i--) {
                if (path.get(i).getMetadata(Metadata.TYPE_NAME) != null) {
                    testClassName = (String) path.get(i).getMetadata(Metadata.TYPE_NAME);
                    break;
                }
            }
            
            result = new TestMethodContext(code, testClassName);
        }
        
        return result;
    }
    
}
