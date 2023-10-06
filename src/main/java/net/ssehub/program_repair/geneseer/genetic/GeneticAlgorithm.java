package net.ssehub.program_repair.geneseer.genetic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import fr.spoonlabs.flacoco.api.result.Location;
import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.JunitEvaluation;
import net.ssehub.program_repair.geneseer.evaluation.ProjectCompiler;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.fault_localization.Flacoco;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class GeneticAlgorithm {

    private static final Logger LOG = Logger.getLogger(GeneticAlgorithm.class.getName());
    
    private static final double WEIGHT_NEGATIVE_TESTS = 10; // W_negT = 10
    private static final double WEIGHT_POSITIVE_TESTS = 1; // W_posT = 1
    private static final int POPULATION_SIZE = 40; // pop_size = 10
    private static final double MUTATION_PROBABILITY = 0.06; // W_mut = 0.06
    
    private static final int MAX_GENERATIONS = 10; // generations = 10
    
    private Random random = new Random(Configuration.INSTANCE.getRandomSeed());
    
    private ProjectCompiler compiler;
    
    private Project project;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Set<String> negativeTests;
    private Set<String> positiveTests;
    
    private int generation;
    private Variant unmodifiedVariant;
    private double bestFitness;
    
    public GeneticAlgorithm(ProjectCompiler compiler, Project project, TemporaryDirectoryManager tempDirManager) {
        
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
    
    private Result runInternal() throws IOException {
        LOG.info("Parsing code");
        createUnmodifiedVariant();
        
        LOG.info("Evaluating unmodified variant");
        Path sourceDir = tempDirManager.createTemporaryDirectory();
        Writer.write(unmodifiedVariant.getAst(), project.getSourceDirectoryAbsolute(), sourceDir);
        
        EvaluationResultAndBinDirectory r = compileAndEvaluateVariant(unmodifiedVariant.getAst());
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
        LinkedHashMap<Location, Double> suspiciousness = flacoco.run(sourceDir, r.binDirectory);
        
        Map<String, Node> classes = new HashMap<>(unmodifiedVariant.getAst().children().size());
        for (Node file : unmodifiedVariant.getAst().children()) {
            String className = file.getMetadata(Metadata.FILENAME).toString().replaceAll("[/\\\\]", ".");
            if (className.endsWith(".java")) {
                className = className.substring(0, className.length() - ".java".length());
            }
            classes.put(className, file);
        }
        
        AtomicInteger suspiciousStatementCount = new AtomicInteger();
        for (Map.Entry<Location, Double> entry : suspiciousness.entrySet()) {
            String className = entry.getKey().getClassName();
            int dollarIndex = className.indexOf('$');
            if (dollarIndex != -1) {
                className = className.substring(0, dollarIndex);
            }
            
            int line = entry.getKey().getLineNumber();
            
            Node ast = classes.get(className);
            if (ast != null) {
                List<Node> matchingStatements = ast.stream()
                        .filter(n -> n.getType() == Type.SINGLE_STATEMENT || n.getType() == Type.COMPOSIT_STATEMENT)
                        .filter(n -> n.hasLine(line))
                        .toList();
                
                if (matchingStatements.isEmpty()) {
                    String cn = className;
                    LOG.warning(() -> "Found no statements for suspicious " + entry.getValue() + " at "
                            + cn + ":" + line);
                    
                } else {
                    long numberOfSingleStatements = matchingStatements.stream()
                            .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
                            .count();
                    if (numberOfSingleStatements > 1) {
                        String cn = className;
                        LOG.warning(() -> "Found " + numberOfSingleStatements + " statements for " + cn
                                + ":" + line + "; adding suspiciousness to all of them");
                    }
                    
                    String cn = className;
                    matchingStatements.stream()
                            .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
                            .forEach(n -> {
                                LOG.fine(() -> "Suspicious " + entry.getValue() + " at " + cn + ":" + line
                                        + " '" + n.getText() + "'");
                                suspiciousStatementCount.incrementAndGet();
                                n.setMetadata(Metadata.SUSPICIOUSNESS, entry.getValue());
                            });
                }
                
            } else {
                String cn = className;
                LOG.warning(() -> "Can't find class name " + cn);
            }
        }
        LOG.info(() -> suspiciousStatementCount.get() + " suspicious statements");
        
        
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
                    double fitness = getFitness(evaluateVariant(variant.getAst()));
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
    
    private void createUnmodifiedVariant() throws IOException {
        Node ast = Parser.parse(project.getSourceDirectoryAbsolute());
        
        this.unmodifiedVariant = new Variant(ast);
    }
    
    private Variant newVariant() throws IOException {
        LOG.fine("Creating new variant");
        Variant variant = new Variant(unmodifiedVariant.getAst());
        mutate(variant);
        
        double fitness = getFitness(evaluateVariant(variant.getAst()));
        LOG.fine(() -> "Fitness: " + fitness);
        variant.setFitness(fitness);
        if (fitness > bestFitness) {
            bestFitness = fitness;
        }
        
        return variant;
    }
    
    private boolean mutate(Variant variant) {
        boolean mutated = false;
        
        Node astRoot = variant.getAst();
        
        List<Node> suspiciousStatements = astRoot.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .toList();
        
        for (int i = 0; i < suspiciousStatements.size(); i++) {
            Node suspicious = suspiciousStatements.get(i);
            if (random.nextDouble() < MUTATION_PROBABILITY && random.nextDouble() < (double) suspicious.getMetadata(Metadata.SUSPICIOUSNESS)) {
                
                Node parent = astRoot.findParent(suspicious).get();
                
                mutated = true;
                Node oldAstRoot = astRoot;
                astRoot = astRoot.cheapClone(parent);
                parent = astRoot.findEquivalentPath(oldAstRoot, parent);
                suspicious = astRoot.findEquivalentPath(oldAstRoot, suspicious);
                variant.setAst(astRoot);
                
                int rand = random.nextInt(2); // TODO: no swap yet
                if (rand == 0) {
                    // delete
                    Node s = suspicious;
                    LOG.fine(() -> "New mutation: deleting " + s.toString());
                    
                    boolean removed = parent.children().remove(suspicious);
                    if (!removed) {
                        LOG.warning(() -> "Failed to delete statement " + s.toString());
                    }
                    
                } else {
                    List<Node> allStatements = astRoot.stream()
                            .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
                            .toList();
                    Node otherStatement = allStatements.get(random.nextInt(allStatements.size())).clone();
                    otherStatement.stream()
                            .filter(n -> n.getType() == Type.LEAF)
                            .forEach(n -> ((LeafNode) n).clearOriginalPosition());
                    otherStatement.setMetadata(Metadata.SUSPICIOUSNESS, suspicious.getMetadata(Metadata.SUSPICIOUSNESS));
                    
                    if (rand == 1) {
                        Node s = suspicious;
                        LOG.fine(() -> "New mutation: inserting " + otherStatement.toString() + " after "
                                + s.toString());
                        
                        // insert
                        int index = parent.children().indexOf(suspicious);
                        parent.children().add(index + 1, otherStatement);
                        
                        
                    } else {
                        // swap
                        // TODO
                    }
                }
                
                suspiciousStatements = astRoot.stream()
                        .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                        .toList();
            }
        }
        
        return mutated;
    }
    
    private static boolean equal(Node n1, Node n2) {
        return n1 == null || n2 == null ? n1 == n2 : n1.getText().equals(n2.getText());
    }
    
    private static String toText(Node n) {
        return n != null ? n.getText() : "null";
    }
    
    private List<Variant> crossover(Variant p1, Variant p2) {
        
        List<Node> p1Suspicious = new LinkedList<>(p1.getAst().stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .toList());
        
        List<Node> p2Suspicious = new LinkedList<>(p2.getAst().stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .toList());
        
        if (p1Suspicious.size() != p2Suspicious.size()) {
            for (int i = 0; i < Math.min(p1Suspicious.size(), p2Suspicious.size()); i++) {
                if (!equal(p1Suspicious.get(i), p2Suspicious.get(i))) {
                    boolean found = false;
                    for (int j = i + 1; j < p2Suspicious.size(); j++) {
                        if (equal(p1Suspicious.get(i), p2Suspicious.get(j))) {
                            found = true;
                            for (int k = 0; k < j - i; k++) {
                                p1Suspicious.add(i, null);
                            }
                            break;
                        }
                    }
                    
                    if (!found) {
                        for (int j = i + 1; j < p1Suspicious.size(); j++) {
                            if (equal(p2Suspicious.get(i), p1Suspicious.get(j))) {
                                for (int k = 0; k < j - i; k++) {
                                    p2Suspicious.add(i, null);
                                }
                                break;
                            }
                        }
                    }
                }
            }

            if (p1Suspicious.size() != p2Suspicious.size()) {
                LOG.warning(() -> "Different number of suspicious statements: " + p1Suspicious.size()
                        + " vs. " + p2Suspicious.size());
            }
        }
        
        List<Integer> diffStatements = new LinkedList<>();
        for (int i = 0; i < Math.min(p1Suspicious.size(), p2Suspicious.size()); i++) {
            if (!equal(p1Suspicious.get(i), p2Suspicious.get(i))) {
                diffStatements.add(i);
            }
        }
        
        if (diffStatements.isEmpty()) {
            LOG.warning("No differences between parents");
        } else {
            LOG.fine(() -> diffStatements.size() + " differing suspicious statements");
        }
        
        Node c1 = p1.getAst();
        Node c2 = p2.getAst();
        
        for (int i : diffStatements) {
            Node p1Node = p1Suspicious.get(i);
            Node p2Node = p2Suspicious.get(i);
            
            if (random.nextBoolean()) {
                LOG.info(() -> "Swapping statements " + toText(p1Suspicious.get(i)) + " and "
                        + toText(p2Suspicious.get(i)));
                
                if (p1Node != null && p2Node != null) {
                    Node oldC1 = c1;
                    c1 = c1.cheapClone(c1.findParent(p1Node).get());
                    p1Node = c1.findEquivalentPath(oldC1, p1Node);
                    Node c1Parent = c1.findParent(p1Node).get();
                    
                    Node oldC2 = c2;
                    c2 = c2.cheapClone(c2.findParent(p2Node).get());
                    p2Node = c2.findEquivalentPath(oldC2, p2Node);
                    Node c2Parent = c2.findParent(p2Node).get();
                    
                    int c1Index = c1Parent.children().indexOf(p1Node);
                    int c2Index = c2Parent.children().indexOf(p2Node);
                    
                    if (c1Index != -1 && c2Index != -1) {
                        c1Parent.children().set(c1Index, p2Node);
                        c2Parent.children().set(c2Index, p1Node);
                        
                    } else {
                        LOG.warning("Failed to find statement in clone");
                    }
                    
                } else if (p1Node != null) {
                    Node oldC1 = c1;
                    c1 = c1.cheapClone(c1.findParent(p1Node).get());
                    p1Node = c1.findEquivalentPath(oldC1, p1Node);
                    Node c1Parent = c1.findParent(p1Node).get();
                    
                    boolean removed = c1Parent.children().remove(p1Node);
                    if (!removed) {
                        Node p = p1Node;
                        LOG.warning(() -> "Failed to remove " + p.getText() + " from " + c1Parent.getText());
                    }
                    
                } else if (p2Node != null) {
                    Node oldC2 = c2;
                    c2 = c2.cheapClone(c2.findParent(p2Node).get());
                    p2Node = c2.findEquivalentPath(oldC2, p2Node);
                    Node c2Parent = c2.findParent(p2Node).get();
                    
                    boolean removed = c2Parent.children().remove(p2Node);
                    if (!removed) {
                        Node p = p2Node;
                        LOG.warning(() -> "Failed to remove " + p.getText() + " from " + c2Parent.getText());
                    }
                }
            }
        }
        
        return List.of(new Variant(c1), new Variant(c2));
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
    
    private EvaluationResult evaluateVariant(Node variant) throws IOException {
        
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
    
    private EvaluationResultAndBinDirectory compileAndEvaluateVariant(Node variant) throws IOException {
        EvaluationResult evalResult = null;
        
        Path sourceDirectory = tempDirManager.createTemporaryDirectory();
        Path binDirectory = tempDirManager.createTemporaryDirectory();
            
        Writer.write(variant, project.getSourceDirectoryAbsolute(), sourceDirectory);
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
