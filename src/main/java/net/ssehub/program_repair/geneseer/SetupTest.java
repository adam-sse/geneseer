package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.evaluation.TestExecutionException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.util.JsonUtils;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class SetupTest {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(SetupTest.class.getName());
    
    public static void main(String[] args) {
        Project project = null;
        try {
            project = Geneseer.initialize(args);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read configuration file", e);
            System.exit(1);
        }
        
        Map<String, Object> result = new HashMap<>();
        
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            Node ast = Parser.parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
            ast.lock();
            LOG.fine(() -> ast.stream().count() + " nodes in AST");
            
            Path sourceDirectory = tempDirManager.createTemporaryDirectory();
            Writer.write(ast, project.getSourceDirectoryAbsolute(), sourceDirectory, project.getEncoding());
            
            Path binDirectory = tempDirManager.createTemporaryDirectory();
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute(),
                    project.getEncoding());
            compiler.setLogOutput(true);
            compiler.compile(sourceDirectory, binDirectory);
                
            JunitEvaluation evaluation = new JunitEvaluation(project.getProjectDirectory(),
                    project.getTestExecutionClassPathAbsolute(), project.getEncoding());
            
            EvaluationResult evaluationResult = evaluation.runTests(binDirectory, project.getTestClassNames());

            LOG.info(() -> evaluationResult.getFailures().size() + " failing tests:");
            List<Map<String, String>> failingTests = new LinkedList<>();
            for (TestResult failure : evaluationResult.getFailures()) {
                LOG.info(() -> "    " + failure + " " + failure.failureMessage());
                
                Map<String, String> failingTest = new HashMap<>();
                failingTest.put("class", failure.testClass());
                failingTest.put("method", failure.testMethod());
                failingTest.put("message", failure.failureMessage());
                failingTests.add(failingTest);
            }
            result.put("failingTests", failingTests);
            if (failingTests.isEmpty()) {
                result.put("result", "NO_FAILING_TESTS");
            } else {
                result.put("result", "FOUND_FAILING_TESTS");
            }
            
        } catch (CompilationException e) {
            LOG.severe("Failed compilation");
            result.put("result", "FAILED_COMPILATION");
            
        } catch (TestExecutionException e) {
            LOG.log(Level.SEVERE, "Failed test execution", e);
            result.put("result", "FAILED_TEST_EXECUTION");
            result.put("exception", e.getMessage());
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IOException", e);
            result.put("result", "IO_EXCEPTION");
            result.put("exception", e.getMessage());
        }
        
        System.out.println(JsonUtils.GSON.toJson(result));
    }
    
}
