package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
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
            boolean compiled = compiler.compile(sourceDirectory, binDirectory);
            if (compiled) {
                
                JunitEvaluation evaluation = new JunitEvaluation();
                
                try {
                    EvaluationResult evaluationResult = evaluation.runTests(project.getProjectDirectory(),
                            project.getTestExecutionClassPathAbsolute(), binDirectory, project.getTestClassNames(),
                            project.getEncoding());

                    LOG.info(() -> evaluationResult.getFailures().size() + " failing tests:");
                    for (TestResult failure : evaluationResult.getFailures()) {
                        LOG.info(() -> "    " + failure + " " + failure.failureMessage());
                    }
                    
                    System.out.println("failing tests:");
                    for (TestResult failure : evaluationResult.getFailures()) {
                        System.out.println(failure.toString());
                    }
                } catch (EvaluationException e) {
                    LOG.log(Level.SEVERE, "Failed evaluation", e);
                    System.out.println("failed evaluation");
                }
            } else {
                LOG.severe("Failed compilation");
                System.out.println("failed compilation");
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IOException", e);
            System.out.println("IOException: " + e.getMessage());
        }
    }
    
}
