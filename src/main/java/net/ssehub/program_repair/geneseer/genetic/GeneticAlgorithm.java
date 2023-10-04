package net.ssehub.program_repair.geneseer.genetic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import gumtree.spoon.AstComparator;
import gumtree.spoon.diff.Diff;
import gumtree.spoon.diff.operations.InsertOperation;
import gumtree.spoon.diff.operations.Operation;
import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.fault_localization.Flacoco;
import net.ssehub.program_repair.geneseer.parsing.CodeModel;
import net.ssehub.program_repair.geneseer.parsing.CodeModelFactory;
import net.ssehub.program_repair.geneseer.util.SpoonUtils;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtPath;

public class GeneticAlgorithm {

    private static final Logger LOG = Logger.getLogger(GeneticAlgorithm.class.getName());
    
    private static final double WEIGHT_NEGATIVE_TESTS = 10; // W_negT
    private static final double WEIGHT_POSITIVE_TESTS = 1; // W_posT
    private static final int POPULATION_SIZE = 10; // pop_size
    private static final double MUTATION_PROBABILITY = 0.12; // W_mut
    
    private static final int MAX_GENERATIONS = 10;
    
    private Random random = new Random(Configuration.INSTANCE.getRandomSeed());
    
    private CodeModelFactory codeModelFactory;
    
    private ProjectCompiler compiler;
    
    private Project project;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Set<String> negativeTests;
    private Set<String> positiveTests;
    
    private List<SuspiciousStatement> suspiciousStatements;
    
    private int generation;
    private Variant unmodifiedVariant;
    private double bestFitness;
    
    public GeneticAlgorithm(CodeModelFactory codeModelFactory, ProjectCompiler compiler, Project project,
            TemporaryDirectoryManager tempDirManager) {
        
        this.codeModelFactory = codeModelFactory;
        this.compiler = compiler;
        this.project = project;
        this.tempDirManager = tempDirManager;
    }
    
    public Result run() {
        Result result;
        try {
            result = runInternal();
        } catch (IOException e) {
            result = Result.ioException(e, generation);
        }
        return result;
    }
    
    void setRandom(Random random) {
        this.random = random;
    }
    
    void createUnmodifiedVariant() throws IOException {
        unmodifiedVariant = new Variant(codeModelFactory.createModel());
    }
    
    private Result runInternal() throws IOException {
        LOG.info("Evaluating unmodified variant");
        
        createUnmodifiedVariant();
        
        Path sourceDir = tempDirManager.createTemporaryDirectory();
        unmodifiedVariant.getCodeModel().write(sourceDir);
        
        EvaluationResultAndBinDirectory r = compileAndEvaluateVariant(unmodifiedVariant.getCodeModel());
        if (r.evaluation == null) {
            return Result.originalUnfit();
        }
        
        negativeTests = new HashSet<>();
        positiveTests = new HashSet<>();
        for (TestResult test : r.evaluation.getExecutedTests()) {
            if (test.isFailure()) {
                negativeTests.add(test.toString());
            } else {
                positiveTests.add(test.toString());
            }
        }
        
        LOG.fine(() -> "Negative tests (" + negativeTests.size() + "): " + negativeTests);
        LOG.fine(() -> "Positive tests (" + positiveTests.size() + "): " + positiveTests);
        
        unmodifiedVariant.setFitness(getFitness(r.evaluation));
        LOG.info(() -> "Fitness of unmodified variant and max fitness: "
                + unmodifiedVariant.getFitness() + " / " + getMaxFitness());
        bestFitness = unmodifiedVariant.getFitness();
        
        LOG.info("Measuring suspiciousness");
        Flacoco flacoco = new Flacoco(project.getProjectDirectory(), project.getTestExecutionClassPathAbsolute());
        flacoco.setExpectedFailures(negativeTests);
        LinkedHashMap<CtPath, Double> suspiciousness = flacoco.run(sourceDir, r.binDirectory);
        
        suspiciousStatements = new ArrayList<>(suspiciousness.size());
        for (Map.Entry<CtPath, Double> entry : suspiciousness.entrySet()) {
            SuspiciousStatement statement = new SuspiciousStatement(entry.getKey(), entry.getValue());
            LOG.fine(() -> "Suspicious: " + statement);
            suspiciousStatements.add(statement);
        }
        
        LOG.info(suspiciousStatements.size() + " suspicious statements");
        
        List<Variant> population = new ArrayList<>(POPULATION_SIZE);
        for (int i = 0; i < POPULATION_SIZE; i++) {
            population.add(newVariant());
        }
        LOG.fine(() -> "Population fitness: " + population.stream().map(Variant::getFitness).toList());
        
        while (indexWithMaxFitness(population) < 0 && ++generation <= MAX_GENERATIONS) {
            int g = generation;
            LOG.info(() -> "Generation " + g);
            
            List<Variant> viable = population.stream()
                    .filter(v -> v.getFitness() > 0.0)
                    .sorted(Comparator.comparingDouble(Variant::getFitness).reversed())
                    .toList();
            population.clear();
            
            double sum = viable.stream().mapToDouble(Variant::getFitness).sum();
            List<Double> cummulativeProbabilityDistribution = new ArrayList<>(viable.size());
            viable.stream()
                    .map(Variant::getFitness)
                    .map(f -> f / sum)
                    .forEach(cummulativeProbabilityDistribution::add);
            double s = 0.0;
            for (int i = 0; i < cummulativeProbabilityDistribution.size(); i++) {
                double value = cummulativeProbabilityDistribution.get(i);
                cummulativeProbabilityDistribution.set(i, value + s);
                s += value;
            }
            
            List<Integer> selected;
            if (viable.size() > POPULATION_SIZE / 2) {
                selected = stochasticUniversalSampling(cummulativeProbabilityDistribution, POPULATION_SIZE / 2);
            } else {
                selected = IntStream.range(0, viable.size()).mapToObj(Integer::valueOf).toList();
            }
            
            for (int i = 0; i < selected.size(); i += 2) {
                Variant p1 = viable.get(i);
                
                if (i + 1 < viable.size()) {
                    Variant p2 = viable.get(i + 1);
                    
                    int ii = i;
                    LOG.fine(() -> "Crossing over " + ii + " and " + (ii + 1));
                    List<Variant> children = crossover(p1, p2);
                    
                    population.add(p1);
                    population.add(p2);
                    population.addAll(children);
                    
                } else {
                    population.add(p1);
                }
            }
            
            while (population.size() < POPULATION_SIZE) {
                population.add(newVariant());
            }
            
            for (Variant variant : population) {
                boolean mutated = mutate(variant);
                
                if (mutated || !variant.hasFitness()) {
                    double fitness = getFitness(evaluateVariant(variant.getCodeModel()));
                    LOG.fine(() -> "Fitness: " + fitness);
                    variant.setFitness(fitness);
                    if (fitness > bestFitness) {
                        bestFitness = fitness;
                    }
                } else {
                    LOG.fine(() -> "Skipping fitness evaluation because variant was not mutated (fitness is "
                            + variant.getFitness() + ")");
                }
            }
            LOG.fine(() -> "Population fitness: " + population.stream().map(Variant::getFitness).toList());
        }

        int index = indexWithMaxFitness(population);
        if (index >= 0) {
            LOG.info(() -> "Variant " + index + " has max fitness");
            return Result.foundFix(unmodifiedVariant.getFitness(), getMaxFitness(), generation);
        } else {
            LOG.info(() -> "Stopping because limit of " + MAX_GENERATIONS + " generations reached");
            return Result.generationLimitReached(MAX_GENERATIONS, unmodifiedVariant.getFitness(), getMaxFitness(), bestFitness);
        }
    }
    
    private Variant newVariant() throws IOException {
        LOG.fine("Creating new variant");
        Variant variant = new Variant(codeModelFactory.createModel());
        mutate(variant);
        
        double fitness = getFitness(evaluateVariant(variant.getCodeModel()));
        LOG.fine(() -> "Fitness: " + fitness);
        variant.setFitness(fitness);
        if (fitness > bestFitness) {
            bestFitness = fitness;
        }
        
        return variant;
    }
    
    private boolean mutate(Variant variant) {
        boolean mutated = false;
        
        for (SuspiciousStatement suspicious : suspiciousStatements) {
            if (random.nextDouble() < MUTATION_PROBABILITY && random.nextDouble() < suspicious.getSuspiciousness()) {
                
                try {
                    CtStatement statement = (CtStatement) SpoonUtils.resolvePath(
                            variant.getCodeModel().getSpoonModel(), suspicious.getPath());
                    
                    mutated = true;
                    
                    int rand = random.nextInt(3);
                    if (rand == 1) {
                        // delete
                        LOG.fine(() -> "New mutation: deleting " + suspicious.toString());
                        
                        statement.delete();
                        
                    } else {
                        List<CtStatement> allStatements = variant.getCodeModel().getSpoonModel()
                                .getElements(e -> SpoonUtils.isStatement(e));
                        CtStatement other = allStatements.get(random.nextInt(allStatements.size()));
                        
                        
                        if (rand == 2) {
                            // insert
                            LOG.fine(() -> "New mutation: inserting " + SpoonUtils.statementToStringWithLocation(other) + " after "
                                    + suspicious.toString());
                            
                            statement.insertAfter(other);
                            
                        } else {
                            // swap
                            LOG.fine(() -> "New mutation: swapping " + suspicious.toString() + " and "
                                    + SpoonUtils.statementToStringWithLocation(other));
                            
                            statement.replace(other);
                            other.replace(statement);
                        }
                    }
                    
                } catch (NoSuchElementException e) {
                    // ignore
                }
            }
        }
        
        return mutated;
    }
    
    private void apply(CtModel model, Operation<?> operation) {
        switch (operation.getAction().getName()) {
        
        case "delete-node":
            try {
                CtElement toDelete = SpoonUtils.resolvePath(model, operation.getSrcNode().getPath());
                toDelete.delete();
                LOG.fine(() -> "Applied delete-node on " + operation.getSrcNode().getPath());
            } catch (NoSuchElementException e) {
                // ignore
                LOG.fine(() -> "Failed to apply delete-node on " + operation.getSrcNode().getPath());
            }
            break;
        
        case "insert-node":
            try {
                InsertOperation insOp = (InsertOperation) operation;
                CtElement parent = SpoonUtils.resolvePath(model, insOp.getSrcNode().getParent().getPath());
                int position = insOp.getSrcNode().getParent().getDirectChildren().indexOf(insOp.getSrcNode());
                
                if (position > 0) {
                    if (position > parent.getDirectChildren().size()) {
                        position = parent.getDirectChildren().size();
                    }
                    
                    CtStatement neighbor = (CtStatement) parent.getDirectChildren().get(position - 1);
                    neighbor.insertAfter((CtStatement) insOp.getSrcNode());
                } else {
                    CtStatement neighbor = (CtStatement) parent.getDirectChildren().get(0);
                    neighbor.insertBefore((CtStatement) insOp.getSrcNode());
                }
                
                LOG.fine(() -> "Applied insert-node on " + operation.getSrcNode().getPath());
            } catch (NoSuchElementException e) {
                // ignore
                LOG.fine(() -> "Failed to apply insert-node on " + operation.getSrcNode().getPath());
            }
            break;
            
        default:
            LOG.warning(() -> "Unknown operation: " + operation.getAction().getName());
            break;
        }
    }
    
    List<Variant> crossover(Variant p1, Variant p2) {
        Variant c1 = new Variant(codeModelFactory.createModel());
        Variant c2 = new Variant(codeModelFactory.createModel());
        
        Diff d1 = new AstComparator().compare(
                unmodifiedVariant.getCodeModel().getSpoonModel().getRootPackage(),
                p1.getCodeModel().getSpoonModel().getRootPackage());
        Diff d2 = new AstComparator().compare(
                unmodifiedVariant.getCodeModel().getSpoonModel().getRootPackage(),
                p2.getCodeModel().getSpoonModel().getRootPackage());
        
        if (!d1.getRootOperations().isEmpty()) {
            int d1Cutoff = random.nextInt(d1.getRootOperations().size());
            for (int i = 0; i < d1.getRootOperations().size(); i++) {
                if (i <= d1Cutoff) {
                    apply(c1.getCodeModel().getSpoonModel(), d1.getRootOperations().get(i));
                } else {
                    apply(c2.getCodeModel().getSpoonModel(), d1.getRootOperations().get(i));
                }
            }
        }
        
        System.out.println(d2.getRootOperations());
        
        if (!d2.getRootOperations().isEmpty()) {
            int d2Cutoff = random.nextInt(d2.getRootOperations().size());
            for (int i = 0; i < d2.getRootOperations().size(); i++) {
                if (i <= d2Cutoff) {
                    apply(c2.getCodeModel().getSpoonModel(), d2.getRootOperations().get(i));
                } else {
                    apply(c1.getCodeModel().getSpoonModel(), d2.getRootOperations().get(i));
                }
            }
        }
        
        return List.of(c1, c2);
    }
    
    private List<Integer> stochasticUniversalSampling(List<Double> cummulativeProbabilityDistribution, int sampleSize) {
        List<Integer> result = new ArrayList<>(sampleSize);
        int i = 0;
        double r = random.nextDouble(1.0 / sampleSize);
        while (result.size() < sampleSize) {
            while (r < cummulativeProbabilityDistribution.get(i)) {
                result.add(i);
                r += 1.0 / sampleSize;
            }
            i++;
        }
        return result;
    }
    
    private EvaluationResult evaluateVariant(CodeModel variant) throws IOException {
        
        EvaluationResultAndBinDirectory result = null;
        try {
            result = compileAndEvaluateVariant(variant);
            return result.evaluation;
        } finally {
            if (result != null) {
                tempDirManager.deleteTemporaryDirectory(result.binDirectory);
            }
        }
    }
    
    private static class EvaluationResultAndBinDirectory {EvaluationResult evaluation; Path binDirectory; }
    
    private EvaluationResultAndBinDirectory compileAndEvaluateVariant(CodeModel variant) throws IOException {
        EvaluationResult evalResult = null;
        
        Path sourceDirectory = tempDirManager.createTemporaryDirectory();
        Path binDirectory = tempDirManager.createTemporaryDirectory();
            
        variant.write(sourceDirectory);
        boolean compiled = compiler.compile(sourceDirectory, binDirectory);
        if (compiled) {
            JunitEvaluation evaluation = new JunitEvaluation();
            
            try {
                evalResult = evaluation.runTests(
                        project.getProjectDirectory(), project.getTestExecutionClassPathAbsolute(), binDirectory, project.getTestClassNames());
                
                
            } catch (EvaluationException e) {
                LOG.log(Level.WARNING, "Failed to run tests on variant", e);
            }
            
        } else {
            LOG.info("Failed to compile modified code");
        }
            
        try {
            tempDirManager.deleteTemporaryDirectory(sourceDirectory);
        } catch (IOException e) {
            // ignore, will be cleaned up later when tempDirManager is closed
        }
        
        EvaluationResultAndBinDirectory result = new EvaluationResultAndBinDirectory();
        result.evaluation = evalResult;
        result.binDirectory = binDirectory;
        return result;
    }
    
    private double getFitness(EvaluationResult evaluation) {
        double fitness;
        if (evaluation != null) {
            
            int numPassingNegative = 0;
            int numPassingPositive = 0;
            for (TestResult test : evaluation.getExecutedTests()) {
                if (!test.isFailure()) {
                    if (negativeTests.contains(test.toString())) {
                        numPassingNegative++;
                    } else if (positiveTests.contains(test.toString())) {
                        numPassingPositive++;
                    } else {
                        LOG.warning(() -> test.toString() + " is neither in positive nor negative test cases");
                    }
                }
            }
            
            fitness = WEIGHT_NEGATIVE_TESTS * numPassingNegative + WEIGHT_POSITIVE_TESTS * numPassingPositive;
        } else {
            fitness = 0.0;
        }
        return fitness;
    }
    
    private double getMaxFitness() {
        return negativeTests.size() * WEIGHT_NEGATIVE_TESTS + positiveTests.size() * WEIGHT_POSITIVE_TESTS;
    }
    
    private int indexWithMaxFitness(List<Variant> population) {
        for (int i = 0; i < population.size(); i++) {
            if (population.get(i).getFitness() >= getMaxFitness()) {
                return i;
            }
        }
        return -1;
    }
    
}
