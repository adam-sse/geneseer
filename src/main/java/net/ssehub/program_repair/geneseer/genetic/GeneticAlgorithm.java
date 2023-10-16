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
import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class GeneticAlgorithm {

    private static final Logger LOG = Logger.getLogger(GeneticAlgorithm.class.getName());
    
    private static final double WEIGHT_NEGATIVE_TESTS = 10; // W_negT = 10
    private static final double WEIGHT_POSITIVE_TESTS = 1; // W_posT = 1
    private static final int POPULATION_SIZE = 40; // pop_size = 10
    private static final double MUTATION_PROBABILITY = 4; // W_mut = 0.06
    
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
        try (Probe measure = Measurement.INSTANCE.start("genetic-algorithm")) {
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
        LOG.info(() -> "Fitness of unmodified variant (" + unmodifiedVariant.getName() + ") and max fitness: "
                + unmodifiedVariant.getFitness() + " / " + getMaxFitness());
        bestFitness = unmodifiedVariant.getFitness();
        
        annotateSuspiciousness(unmodifiedVariant, sourceDir, r.binDirectory);
        
        List<Variant> population = new ArrayList<>(POPULATION_SIZE);
        for (int i = 0; i < POPULATION_SIZE; i++) {
            population.add(newVariant());
        }
        LOG.fine(() -> "Population fitness: " + population.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
        
        while (indexWithMaxFitness(population) < 0 && ++generation <= MAX_GENERATIONS) {
            LOG.info(() -> "Generation " + generation);
            
            List<Variant> viable = population.stream()
                    .filter(v -> v.getFitness() > 0.0)
                    .sorted(Comparator.comparingDouble(Variant::getFitness).reversed())
                    .toList();
            population.clear();
            LOG.fine(() -> "Viable: " + viable.stream()
                    .map(v -> v.getName() + "(" + v.getFitness() + ")")
                    .toList());
            
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
                    LOG.fine(() -> variant.toString());
                    if (fitness > bestFitness) {
                        bestFitness = fitness;
                        LOG.info(() -> "New best variant: " + variant.getName());
                    }
                } else {
                    LOG.fine(() -> "Skipping fitness evaluation because variant was not mutated: " + variant);
                }
            }
            LOG.fine(() -> "Population fitness: " + population.stream()
                    .map(v -> v.getName() + "(" + v.getFitness() + ")")
                    .toList());
        }

        int index = indexWithMaxFitness(population);
        if (index >= 0) {
            LOG.info(() -> "Variant with max fitness: " + population.get(index));
            return Result.foundFix(unmodifiedVariant.getFitness(), getMaxFitness(), generation);
        } else {
            LOG.info(() -> "Stopping because limit of " + MAX_GENERATIONS + " generations reached");
            return Result.generationLimitReached(MAX_GENERATIONS, unmodifiedVariant.getFitness(), getMaxFitness(), bestFitness);
        }
    }

    private void annotateSuspiciousness(Variant variant, Path variantSourceDir, Path variantBinDir) {
        LOG.info("Measuring suspiciousness");
        Flacoco flacoco = new Flacoco(project.getProjectDirectory(), project.getTestExecutionClassPathAbsolute());
        flacoco.setExpectedFailures(negativeTests);
        LinkedHashMap<Location, Double> suspiciousness = flacoco.run(variantSourceDir, variantBinDir);
        
        Map<String, Node> classes = new HashMap<>(variant.getAst().childCount());
        for (Node file : variant.getAst().childIterator()) {
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
                        .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
                        .filter(n -> n.hasLine(line))
                        .toList();
                
                if (matchingStatements.isEmpty()) {
                    String cn = className;
                    LOG.info(() -> "Found no statements for suspicious " + entry.getValue() + " at "
                            + cn + ":" + line);
                } else if (matchingStatements.size() > 1) {
                    String cn = className;
                    LOG.warning(() -> "Found " + matchingStatements.size() + " statements for " + cn
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
                
            } else {
                String cn = className;
                LOG.warning(() -> "Can't find class name " + cn);
            }
        }
        LOG.info(() -> suspiciousStatementCount.get() + " suspicious statements");
    }
    
    private void createUnmodifiedVariant() throws IOException {
        Node ast = Parser.parse(project.getSourceDirectoryAbsolute());
        ast.lock();
        LOG.fine(() -> ast.stream().count() + " nodes in AST");
        
        this.unmodifiedVariant = new Variant(ast);
    }
    
    private Variant newVariant() throws IOException {
        Variant variant = new Variant(unmodifiedVariant.getAst());
        LOG.fine("Creating new variant " + variant.getName());
        mutate(variant);
        
        double fitness = getFitness(evaluateVariant(variant.getAst()));
        variant.setFitness(fitness);
        LOG.fine(() -> variant.toString());
        if (fitness > bestFitness) {
            bestFitness = fitness;
            LOG.info(() -> "New best variant: " + variant.getName());
        }
        
        return variant;
    }
    
    private boolean mutate(Variant variant) {
        boolean mutated = false;
        
        Node astRoot = variant.getAst();
        
        List<Node> suspiciousStatements = astRoot.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .toList();
        
        double mutationProbability = MUTATION_PROBABILITY / suspiciousStatements.size();
        List<Node> ss = suspiciousStatements;
        LOG.fine(() -> ss.size() + " suspicious statements -> mutation probability " + mutationProbability);
        for (int i = 0; i < suspiciousStatements.size(); i++) {
            Node suspicious = suspiciousStatements.get(i);
            if (random.nextDouble() < mutationProbability && random.nextDouble() < (double) suspicious.getMetadata(Metadata.SUSPICIOUSNESS)) {
                
                mutated = true;
                Node oldAstRoot = astRoot;
                astRoot = astRoot.cheapClone(suspicious);
                variant.setAst(astRoot);
                suspicious = astRoot.findEquivalentPath(oldAstRoot, suspicious);
                Node parent = astRoot.findParent(suspicious).get();
                
                int rand = random.nextInt(2); // TODO: no swap yet
                if (rand == 0) {
                    // delete
                    Node s = suspicious;
                    LOG.fine(() -> "Mutating " + variant.getName() + ": deleting " + s.toString());
                    
                    boolean removed = parent.remove(suspicious);
                    if (!removed) {
                        LOG.warning(() -> "Failed to delete statement " + s.toString());
                    } else {
                        variant.addMutation("del " + s.toString());
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
                        LOG.fine(() -> "Mutating " + variant.getName() + ": inserting " + otherStatement.toString()
                                + " before " + s.toString());
                        
                        // insert
                        int index = parent.indexOf(suspicious);
                        parent.add(index, otherStatement);
                        variant.addMutation("ins " + otherStatement.toString() + " before " + s.toString());
                        
                        
                    } else {
                        // swap
                        // TODO
                    }
                }
                
                astRoot.lock();
                suspiciousStatements = astRoot.stream()
                        .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                        .toList();
            }
        }
        
        if (!mutated) {
            LOG.fine(() -> "No new mutations added to " + variant.getName());
        }
        
        return mutated;
    }
    
    private List<Variant> crossover(Variant p1, Variant p2) {
        LOG.fine(() -> "Crossing over " + p1 + " and " + p2);
        
        List<Node> p1Parents = new LinkedList<>();
        List<Node> p2Parents = new LinkedList<>();
        
        p1.getAst().stream()
              .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
              .map(node -> p1.getAst().findParent(node).get())
              .forEach(p1Parent -> {
                  Node p2Parent = p2.getAst().findEquivalentPath(p1.getAst(), p1Parent);
                  
                  if (p2Parent != null) {
                      if (!p1Parent.getText().equals(p2Parent.getText()) && !p1Parents.contains(p1Parent)) {
                          p1Parents.add(p1Parent);
                          p2Parents.add(p2Parent);
                      }
                  } else {
                      LOG.warning(() -> "Can't find parent of statement");
                  }
                  
              });
        p2.getAst().stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .map(node -> p2.getAst().findParent(node).get())
                .forEach(p2Parent -> {
                    Node p1Parent = p1.getAst().findEquivalentPath(p2.getAst(), p2Parent);
                    
                    if (p1Parent != null) {
                        if (!p1Parent.getText().equals(p2Parent.getText()) && !p1Parents.contains(p1Parent)) {
                            p1Parents.add(p1Parent);
                            p2Parents.add(p2Parent);
                        }
                    } else {
                        LOG.warning(() -> "Can't find parent of statement");
                    }
                    
                });
        
        
        LOG.fine(() -> "Found " + p1Parents.size() + " blocks with different content");
        
        Node c1 = p1.getAst();
        Node c2 = p2.getAst();
        
        for (int i = 0; i < p1Parents.size(); i++) {
            Node c1Parent = c1.findEquivalentPath(p1.getAst(), p1Parents.get(i));
            Node c2Parent = c2.findEquivalentPath(p2.getAst(), p2Parents.get(i));
            
            if (c1Parent == null || c2Parent == null) {
                LOG.warning(() -> "Couldn't find block in modified child");
                continue;
            }
            
            int cutoff = random.nextInt(Math.min(c1Parent.childCount(), c2Parent.childCount()) + 1);

            Node newC1Parent = new InnerNode(c1Parent.getType());
            Node newC2Parent = new InnerNode(c2Parent.getType());
            
            for (int j = 0; j < Math.min(c1Parent.childCount(), c2Parent.childCount()); j++) {
                if (j < cutoff) {
                    newC1Parent.add(c1Parent.get(j));
                    newC2Parent.add(c2Parent.get(j));
                } else {
                    newC2Parent.add(c1Parent.get(j));
                    newC1Parent.add(c2Parent.get(j));
                }
            }
            
            if (c1Parent.childCount() > c2Parent.childCount()) {
                for (int j = c2Parent.childCount(); j < c1Parent.childCount(); j++) {
                    newC2Parent.add(c1Parent.get(j));
                }
            } else if (c2Parent.childCount() > c1Parent.childCount()) {
                for (int j = c1Parent.childCount(); j < c2Parent.childCount(); j++) {
                    newC1Parent.add(c2Parent.get(j));
                }
            }
            
            Node oldC1 = c1;
            c1 = c1.cheapClone(c1Parent);
            c1Parent = c1.findEquivalentPath(oldC1, c1Parent);
            Node c1ParentParent = c1.findParent(c1Parent).get();
            c1ParentParent.set(c1ParentParent.indexOf(c1Parent), newC1Parent);
            
            Node oldC2 = c2;
            c2 = c2.cheapClone(c2Parent);
            c2Parent = c2.findEquivalentPath(oldC2, c2Parent);
            Node c2ParentParent = c2.findParent(c2Parent).get();
            c2ParentParent.set(c2ParentParent.indexOf(c2Parent), newC2Parent);
            
            c1.lock();
            c2.lock();
        }
        
        Variant v1 = new Variant(c1);
        v1.addMutation("child " + p1.getName() + " " + p2.getName());
        
        Variant v2 = new Variant(c2);
        v2.addMutation("child " + p2.getName() + " " + p1.getName());
        
        LOG.fine(() -> "Created child of " + p1.getName() + " and " + p2.getName() + ": " + v1);
        LOG.fine(() -> "Created child of " + p1.getName() + " and " + p2.getName() + ": " + v2);
        return List.of(v1, v2);
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
            LOG.info("Failed to compile variant");
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
