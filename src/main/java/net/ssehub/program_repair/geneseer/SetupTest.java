package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;
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
            project = Geneseer.readProjectFromCommandLine(args);
        } catch (IllegalArgumentException e) {
            LOG.severe("Command line arguments invalid: " + e.getMessage());
            LOG.severe("Usage: <projectDirectory> <sourceDirectory> <compilationClasspath> <testExecutionClassPath> "
                    + "<testClassName>...");
            System.exit(1);
        }
        
        LOG.info("Project:");
        LOG.info("    base directory: " + project.getProjectDirectory());
        LOG.info("    source directory: " + project.getSourceDirectory());
        LOG.info("    compilation classpath: " + project.getCompilationClasspath());
        LOG.info("    test execution classpath: " + project.getTestExecutionClassPath());
        LOG.info("    test classes (" + project.getTestClassNames().size() + "): " + project.getTestClassNames());
        LOG.fine("Using encoding " + Configuration.INSTANCE.getEncoding());
        
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            
            Node ast = Parser.parse(project.getSourceDirectoryAbsolute());
            ast.lock();
            LOG.fine(() -> ast.stream().count() + " nodes in AST");
            
            Path sourceDirectory = tempDirManager.createTemporaryDirectory();
            Writer.write(ast, project.getSourceDirectoryAbsolute(), sourceDirectory);
            
            Path binDirectory = tempDirManager.createTemporaryDirectory();
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute());
            compiler.setLogOutput(true);
            boolean compiled = compiler.compile(sourceDirectory, binDirectory);
            if (compiled) {
                
                JunitEvaluation evaluation = new JunitEvaluation();
                
                try {
                    EvaluationResult evaluationResult = evaluation.runTests(project.getProjectDirectory(),
                            project.getTestExecutionClassPathAbsolute(), binDirectory, project.getTestClassNames());
                    
                    System.out.println("failing tests:");
                    
                    for (TestResult failure : evaluationResult.getFailures()) {
                        System.out.println(failure.toString());
                    }
                    
                } catch (EvaluationException e) {
                    System.out.println("failed evaluation");
                }
                
            } else {
                System.out.println("failed compilation");
            }
            
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }
    
}
