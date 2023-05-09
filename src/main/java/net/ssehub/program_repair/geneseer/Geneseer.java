package net.ssehub.program_repair.geneseer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.evaluation.TestFailure;
import net.ssehub.program_repair.geneseer.fault_localization.Flacoco;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.mutations.IMutation;
import net.ssehub.program_repair.geneseer.mutations.MutationException;
import net.ssehub.program_repair.geneseer.mutations.RemoveStatementMutation;
import net.ssehub.program_repair.geneseer.parsing.CodeModel;
import net.ssehub.program_repair.geneseer.parsing.CodeModelFactory;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;
import spoon.reflect.code.CtStatement;
import spoon.reflect.cu.SourcePosition;

public class Geneseer {
    
    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Geneseer.class.getName());
    
    public static void main(String[] args) throws IOException {
        Project project = null;
        try {
            project = readProjectFromCommandLine(args);
        } catch (IllegalArgumentException e) {
            LOG.severe("Command line arguments invalid: " + e.getMessage());
            LOG.severe("Usage: <projectDirectory> <sourceDirectory> <compilationClasspath> <testExecutionClassPath> <testClassName>...");
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
            
            CodeModelFactory codeModel = new CodeModelFactory(project.getSourceDirectoryAbsolute(), project.getCompilationClasspathAbsolute());
            ProjectCompiler compiler = new ProjectCompiler(project.getCompilationClasspathAbsolute());
            
            LOG.info("Compiling and evaluating unmodified variant");
            CodeModel unmodifiedModel = codeModel.createModel();
            
            Path sourceDir = tempDirManager.createTemporaryDirectory();
            unmodifiedModel.write(sourceDir);
            
            // dump model into "new" source directory, as line numbers change (preserving line numbers is buggy)
            codeModel = new CodeModelFactory(sourceDir, project.getCompilationClasspathAbsolute());
            unmodifiedModel = codeModel.createModel();
            
            EvaluationResultAndBinDirectory res = compileAndEvaluateVariant(unmodifiedModel, project, compiler, tempDirManager);
            int originalFitness = res.fitness;
            Path unmodifiedBindir = res.binDirectory;
            
            LOG.info(() -> "Fitness of original variant without mutations: " + originalFitness);
            if (originalFitness == Integer.MAX_VALUE || res.failingTests == null) {
                LOG.severe("Failed to compile or run tests on unmodified code");
                System.out.print(originalFitness);
                return;
            }
            
            LOG.info("Measuring suspiciousness");
            Flacoco flacoco = new Flacoco(project.getProjectDirectory(), project.getTestExecutionClassPathAbsolute());
            flacoco.setExpectedFailures(res.failingTests);
            LinkedHashMap<CtStatement, Double> suspiciousness;
            try {
                suspiciousness = flacoco.run(sourceDir, unmodifiedBindir);
            } catch (EvaluationException e) {
                LOG.severe("Flacoco failures do not equal previous evaluation result");
                System.out.print(originalFitness);
                return;
            }
            
            for (Map.Entry<CtStatement, Double> entry : suspiciousness.entrySet()) {
                SourcePosition pos = entry.getKey().getPosition();

                LOG.fine(() -> "Suspicous: " + String.format(Locale.ROOT, "%2.2f %%", entry.getValue() * 100) + " "
                        + sourceDir.relativize(pos.getFile().toPath()) + ": " + pos.getLine() + " "
                        + entry.getKey().getClass().getSimpleName() + " "
                        + entry.getKey().toString().replaceAll("[\n\r]", "[\\\\n]"));
            }
            
            int bestFitness = originalFitness;
            IMutation bestMutation = null;

            int i = 0;
            for (CtStatement toRemove : suspiciousness.keySet()) {
                i++;
                RemoveStatementMutation mutation = new RemoveStatementMutation(toRemove);
                int ii = i;
                LOG.info(() -> "Mutation " + ii + "/" + suspiciousness.size() + ": " + mutation.textDescription());
                
                CodeModel mutated = codeModel.createModel();
                
                try {
                    mutation.apply(mutated.getSpoonModel());
                    int fitness = evaluateVariant(mutated, project, compiler, tempDirManager);
                    LOG.info(() -> "Mutant fitness: " + fitness);
                    
                    if (fitness < bestFitness) {
                        bestFitness = fitness;
                        bestMutation = mutation;
                        
                        if (fitness == 0) {
                            break;
                        }
                    }
                    
                } catch (MutationException e) {
                    LOG.log(Level.WARNING, "Failed to apply mutation", e);
                }
            }
            
            System.out.print(originalFitness + ";" + bestFitness + ";"
                    + (bestMutation != null ? bestMutation.textDescription() : "<no mutation>"));
            
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "IO exception", e);
            
        } finally {
            System.out.println();
            LOG.info("Timing measurements:");
            for (Map.Entry<String, Long> entry : Measurement.INSTANCE.finishedProbes()) {
                LOG.info(() -> "    " + entry.getKey() + ": " + entry.getValue() + " ms");
            }
        }
    }

    
    static class EvaluationResultAndBinDirectory {int fitness; List<TestFailure> failingTests; Path binDirectory; }
    
    private static int evaluateVariant(CodeModel variant, Project project, ProjectCompiler compiler,
    TemporaryDirectoryManager tempDirManager) throws IOException {
        
        EvaluationResultAndBinDirectory result = null;
        try {
            result = compileAndEvaluateVariant(variant, project, compiler, tempDirManager);
            return result.fitness;
        } finally {
            if (result != null) {
                tempDirManager.deleteTemporaryDirectory(result.binDirectory);
            }
        }
    }
    
    private static EvaluationResultAndBinDirectory compileAndEvaluateVariant(CodeModel variant, Project project,
            ProjectCompiler compiler, TemporaryDirectoryManager tempDirManager) throws IOException {
        
        int fitness;
        List<TestFailure> failingTests = null;
        
        Path sourceDirectory = tempDirManager.createTemporaryDirectory();
        Path binDirectory = tempDirManager.createTemporaryDirectory();
            
        variant.write(sourceDirectory);
        boolean compiled = compiler.compile(sourceDirectory, binDirectory);
        if (compiled) {
            JunitEvaluation evaluation = new JunitEvaluation();
            
            try {
                EvaluationResult evalResult = evaluation.runTests(
                        project.getProjectDirectory(), project.getTestExecutionClassPathAbsolute(), binDirectory, project.getTestClassNames());
                
                failingTests = evalResult.getFailures();
                fitness = failingTests.size();
                
            } catch (EvaluationException e) {
                LOG.log(Level.WARNING, "Failed to run tests on mutant", e);
                
                fitness = Integer.MAX_VALUE;
            }
            
        } else {
            LOG.info("Failed to compile modified code");
            fitness = Integer.MAX_VALUE;
        }
            
        try {
            tempDirManager.deleteTemporaryDirectory(sourceDirectory);
        } catch (IOException e) {
            // ignore, will be cleaned up later when tempDirManager is closed
        }
        
        EvaluationResultAndBinDirectory result = new EvaluationResultAndBinDirectory();
        result.fitness = fitness;
        result.failingTests = failingTests;
        result.binDirectory = binDirectory;
        return result;
    }
    
    private static Project readProjectFromCommandLine(String[] args) throws IllegalArgumentException {
        if (args.length < 5) {
            throw new IllegalArgumentException("Too few arguments supplied");
        }
        
        Path projectDirectory = Path.of(args[0]);
        Path sourceDirectory = Path.of(args[1]);
        List<Path> compilationClasspath = readClasspath(args[2]);
        List<Path> testExecutionClassPath = readClasspath(args[3]);
        List<String> testClassNames = Arrays.stream(args).skip(4).toList();
        
        return new Project(projectDirectory, sourceDirectory, compilationClasspath,
                testExecutionClassPath, testClassNames);
    }
    
    private static List<Path> readClasspath(String combined) {
        List<Path> classpath = new LinkedList<>();
        
        if (!combined.isEmpty()) {
            char seperator;
            if (combined.indexOf(':') != -1 && combined.indexOf(';') == -1) {
                seperator = ':';
            } else {
                seperator = File.pathSeparatorChar;
            }
            
            for (String element : combined.split(String.valueOf(seperator))) {
                classpath.add(Path.of(element));
            }
            
        }
        
        return classpath;
    }
    
}
