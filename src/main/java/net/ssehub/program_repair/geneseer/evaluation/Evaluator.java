package net.ssehub.program_repair.geneseer.evaluation;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class Evaluator {

    private TemporaryDirectoryManager tempDirManager;
    
    private ProjectCompiler compiler;
    
    private JunitEvaluation junit;
    
    private Path originalSourceDirectory;
    
    private Charset encoding;
    
    private List<String> testClassNames;
    
    private boolean keepBinDirectory;
    
    private Path lastBinDirectory;
    
    public Evaluator(Project project, ProjectCompiler compiler, JunitEvaluation junit,
            TemporaryDirectoryManager tempDirManager) {
        this.tempDirManager = tempDirManager;
        this.compiler = compiler;
        this.junit = junit;
        this.originalSourceDirectory = project.getSourceDirectory();
        this.encoding = project.getEncoding();
        this.testClassNames = project.getTestClassNames();
    }
    
    public void setKeepBinDirectory(boolean keepBinDirectory) {
        this.keepBinDirectory = keepBinDirectory;
    }
    
    public Path getLastBinDirectory() {
        return lastBinDirectory;
    }
    
    public void setLogCompilerOutput(boolean logOutput) {
        compiler.setLogOutput(logOutput);
    }

    public EvaluationResult evaluate(Node ast) throws CompilationException, TestExecutionException {
        Path sourceDirectory = null;
        Path binDirectory = null;
        try {
            sourceDirectory = tempDirManager.createTemporaryDirectory();
            binDirectory = tempDirManager.createTemporaryDirectory();
            if (keepBinDirectory) {
                lastBinDirectory = binDirectory;
            }
            Writer.write(ast, originalSourceDirectory, sourceDirectory, encoding);
            
            compiler.compile(sourceDirectory, binDirectory);
            return junit.runTests(binDirectory, testClassNames);
            
        } catch (IOException e) {
            throw new CompilationException("Failed to write code", e);
            
        } finally {
            try {
                if (sourceDirectory != null) {
                    tempDirManager.deleteTemporaryDirectory(sourceDirectory);
                }
                if (!keepBinDirectory && binDirectory != null) {
                    tempDirManager.deleteTemporaryDirectory(binDirectory);
                }
            } catch (IOException e) {
                // ignore, will be cleaned up later when tempDirManager is closed
            }
        }
    }
    
}
