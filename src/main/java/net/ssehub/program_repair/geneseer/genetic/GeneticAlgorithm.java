package net.ssehub.program_repair.geneseer.genetic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Configuration.MutationScope;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.Evaluator;
import net.ssehub.program_repair.geneseer.evaluation.TestExecutionException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.fault_localization.FaultLocalization;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class GeneticAlgorithm {

    private static final Logger LOG = Logger.getLogger(GeneticAlgorithm.class.getName());
    
    private Random random = new Random(Configuration.INSTANCE.getRandomSeed());
    
    private Project project;
    
    private Evaluator evaluator;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Set<String> negativeTests;
    private Set<String> positiveTests;
    
    private int generation;
    private Variant unmodifiedVariant;
    private double bestFitness;
    private Variant bestVariant;
    
    public GeneticAlgorithm(Project project, Evaluator evaluator, TemporaryDirectoryManager tempDirManager) {
        this.evaluator = evaluator;
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
        boolean originalIsFit = evaluateUnmodifiedOriginal();
        
        Result result;
        if (originalIsFit) {
            
            LOG.info("Creating initial population");
            List<Variant> population = createInitialPopulation();
            
            while (indexWithMaxFitness(population) < 0 && ++generation <= Configuration.INSTANCE.getGenerationLimit()) {
                singleGeneration(population);
            }
            
            int index = indexWithMaxFitness(population);
            if (index >= 0) {
                LOG.info(() -> "Variant with max fitness: " + population.get(index));
                result = Result.foundFix(unmodifiedVariant.getFitness(), getMaxFitness(), generation);
            } else {
                LOG.info(() -> "Stopping because limit of " + Configuration.INSTANCE.getGenerationLimit()
                        + " generations reached");
                result = Result.generationLimitReached(Configuration.INSTANCE.getGenerationLimit(),
                        unmodifiedVariant.getFitness(), getMaxFitness(), bestFitness);
            }
            
            try {
                logDiffOfBestVariant();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to compute diff of best variant", e);
            }
        } else {
            result = Result.originalUnfit();
        }
        
        return result;
    }

    private void createUnmodifiedVariant() throws IOException {
        Node ast = Parser.parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
        ast.lock();
        LOG.fine(() -> ast.stream().count() + " nodes in AST");
        
        this.unmodifiedVariant = new Variant(ast);
    }

    private boolean evaluateUnmodifiedOriginal() {
        boolean originalIsFit;
        Path binDirectory = null;
        
        try {
            evaluator.setLogCompilerOutput(true);
            evaluator.setKeepBinDirectory(true);
            
            EvaluationResult evaluation = evaluator.evaluate(unmodifiedVariant.getAst());
            binDirectory = evaluator.getLastBinDirectory();
            
            evaluator.setKeepBinDirectory(false);
            evaluator.setLogCompilerOutput(false); // only log compiler output for unmodified original
            
            originalIsFit = true;
            
            negativeTests = new HashSet<>();
            positiveTests = new HashSet<>();
            for (TestResult test : evaluation.getExecutedTests()) {
                if (test.isFailure()) {
                    negativeTests.add(test.toString());
                } else {
                    positiveTests.add(test.toString());
                }
            }
            
            LOG.fine(() -> "Negative tests (" + negativeTests.size() + "): " + negativeTests);
            LOG.fine(() -> "Positive tests (" + positiveTests.size() + "): " + positiveTests);
            
            unmodifiedVariant.setFitness(getFitness(evaluation));
            LOG.info(() -> "Fitness of unmodified variant (" + unmodifiedVariant.getName() + ") and max fitness: "
                    + unmodifiedVariant.getFitness() + " / " + getMaxFitness());
            bestFitness = unmodifiedVariant.getFitness();
            bestVariant = unmodifiedVariant;
            
            FaultLocalization faultLocalization = new FaultLocalization(project.getProjectDirectory(),
                    project.getTestExecutionClassPathAbsolute(), project.getEncoding());
            faultLocalization.measureAndAnnotateSuspiciousness(unmodifiedVariant.getAst(), binDirectory,
                    evaluation.getExecutedTests());
            
        } catch (CompilationException e) {
            LOG.log(Level.SEVERE, "Failed compilation of unmodified original", e);
            originalIsFit = false;
            
        } catch (TestExecutionException e) {
            LOG.log(Level.SEVERE, "Failed running tests on unmodified original", e);
            originalIsFit = false;
        }
        
        try {
            if (binDirectory != null) {
                tempDirManager.deleteTemporaryDirectory(binDirectory);
            }
        } catch (IOException e) {
            // ignore, will be cleaned up later when tempDirManager is closed
        }
        return originalIsFit;
    }

    private List<Variant> createInitialPopulation() throws IOException {
        List<Variant> population = new ArrayList<>(Configuration.INSTANCE.getPopulationSize());
        for (int i = 0; i < Configuration.INSTANCE.getPopulationSize(); i++) {
            population.add(newVariant());
        }
        LOG.fine(() -> "Population fitness: " + population.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
        return population;
    }

    private void singleGeneration(List<Variant> population) throws IOException {
        LOG.info(() -> "Generation " + generation);
        
        List<Variant> viable = population.stream()
                .filter(v -> v.getFitness() > 0.0)
                .sorted(Comparator.comparingDouble(Variant::getFitness).reversed())
                .toList();
        population.clear();
        LOG.fine(() -> "Viable: " + viable.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
        
        List<Double> cummulativeProbabilityDistribution = calculateCummulativeProbabilityDistribution(viable);
        
        List<Integer> selected;
        if (viable.size() > Configuration.INSTANCE.getPopulationSize() / 2) {
            selected = stochasticUniversalSampling(cummulativeProbabilityDistribution,
                    Configuration.INSTANCE.getPopulationSize() / 2);
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
        
        while (population.size() < Configuration.INSTANCE.getPopulationSize()) {
            population.add(newVariant());
        }
        
        for (Variant variant : population) {
            boolean mutated = mutate(variant);
            
            if (mutated || !variant.hasFitness()) {
                measureFitness(variant);
            } else {
                LOG.fine(() -> "Skipping fitness evaluation because variant was not mutated: " + variant);
            }
        }
        LOG.fine(() -> "Population fitness: " + population.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
    }

    private Variant newVariant() throws IOException {
        Variant variant = new Variant(unmodifiedVariant.getAst());
        LOG.fine("Creating new variant " + variant.getName());
        mutate(variant);
        measureFitness(variant);
        return variant;
    }
    
    private boolean mutate(Variant variant) {
        boolean mutated = false;
        
        Node astRoot = variant.getAst();
        
        List<Node> suspiciousStatements = astRoot.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .toList();
        
        double mutationProbability = Configuration.INSTANCE.getMutationProbability() / suspiciousStatements.size();
        List<Node> ss = suspiciousStatements;
        LOG.fine(() -> ss.size() + " suspicious statements -> mutation probability " + mutationProbability);
        for (int i = 0; i < suspiciousStatements.size(); i++) {
            Node suspicious = suspiciousStatements.get(i);
            if (random.nextDouble() < mutationProbability 
                    && random.nextDouble() < (double) suspicious.getMetadata(Metadata.SUSPICIOUSNESS)) {
                
                mutated = true;
                
                astRoot = singleMutation(variant, astRoot, suspicious);
                
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

    private Node singleMutation(Variant variant, Node astRoot, Node suspicious) {
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
            Node otherStatement = selectOtherStatement(astRoot, suspicious).clone();
            otherStatement.stream()
                    .filter(n -> n.getType() == Type.LEAF)
                    .forEach(n -> ((LeafNode) n).clearOriginalPosition());
            otherStatement.setMetadata(Metadata.SUSPICIOUSNESS,
                    suspicious.getMetadata(Metadata.SUSPICIOUSNESS));
            
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
        return astRoot;
    }

    private Node findFileNode(Node astRoot, Node node) {
        List<Node> path = astRoot.getPath(node);

        Node file = path.get(1);
        if (file.getType() != Type.COMPILATION_UNIT) {
            LOG.warning(() -> "Element doesn't have filename, although it should be a file: " + file.toString());
        }
        
        return file;
    }
    
    private Node selectOtherStatement(Node astRoot, Node suspiciousStatement) {
        if (Configuration.INSTANCE.getStatementScope() == MutationScope.FILE) {
            astRoot = findFileNode(astRoot, suspiciousStatement);
        }
        
        List<Node> allStatements = astRoot.stream()
                .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
                .toList();
        Node otherStatement = allStatements.get(random.nextInt(allStatements.size()));
        return otherStatement;
    }
    
    private List<Variant> crossover(Variant p1, Variant p2) {
        LOG.fine(() -> "Crossing over " + p1 + " and " + p2);
        
        List<Node> p1Parents = new LinkedList<>();
        List<Node> p2Parents = new LinkedList<>();
        findMatchingModifiedBlocks(p1.getAst(), p2.getAst(), p1Parents, p2Parents);
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

    private void findMatchingModifiedBlocks(Node node1, Node node2, List<Node> blocks1, List<Node> blocks2) {
        boolean matched = false;
        if (containsSuspiciousChild(node1) || containsSuspiciousChild(node2)) {
            if (!node1.getText().equals(node2.getText())) {
                blocks1.add(node1);
                blocks2.add(node2);
                matched = true;
            }
        }
        
        if (!matched) {
            for (int i = 0; i < Math.min(node1.childCount(), node2.childCount()); i++) {
                findMatchingModifiedBlocks(node1.get(i), node2.get(i), blocks1, blocks2);
            }
        }
    }

    private boolean containsSuspiciousChild(Node node) {
        boolean result = false;
        for (Node child : node.childIterator()) {
            if (child.getMetadata(Metadata.SUSPICIOUSNESS) != null) {
                result = true;
                break;
            }
        }
        return result;
    }
    
    private List<Double> calculateCummulativeProbabilityDistribution(List<Variant> variants) {
        double sum = variants.stream().mapToDouble(Variant::getFitness).sum();
        
        List<Double> cummulativeProbabilityDistribution = new ArrayList<>(variants.size());
        variants.stream()
                .map(Variant::getFitness)
                .map(f -> f / sum)
                .forEach(cummulativeProbabilityDistribution::add);
        
        double s = 0.0;
        for (int i = 0; i < cummulativeProbabilityDistribution.size(); i++) {
            double value = cummulativeProbabilityDistribution.get(i);
            cummulativeProbabilityDistribution.set(i, value + s);
            s += value;
        }
        
        return cummulativeProbabilityDistribution;
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
    
    private void logDiffOfBestVariant() throws IOException {
        String diff = AstDiff.getDiff(unmodifiedVariant.getAst(), bestVariant.getAst(), tempDirManager,
                project.getEncoding());
        
        LOG.info(() -> "Best variant " + bestVariant + ":\n" + diff);
    }
    
    private void measureFitness(Variant variant) {
        double fitness;
        try {
            fitness = getFitness(evaluator.evaluate(variant.getAst()));
            
        } catch (CompilationException e) {
            fitness = 0;
            
        } catch (TestExecutionException e) {
            LOG.log(Level.WARNING, "Failed running tests on variant", e);
            fitness = 0;
        }
        
        variant.setFitness(fitness);
        
        LOG.fine(() -> variant.toString());
        if (fitness > bestFitness) {
            bestFitness = fitness;
            bestVariant = variant;
            LOG.info(() -> "New best variant: " + variant.getName());
        }
    }
    
    private double getFitness(EvaluationResult evaluation) {
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
        
        return Configuration.INSTANCE.getNegativeTestsWeight() * numPassingNegative
                + Configuration.INSTANCE.getPositiveTestsWeight() * numPassingPositive;
    }
    
    private double getMaxFitness() {
        return negativeTests.size() * Configuration.INSTANCE.getNegativeTestsWeight()
                + positiveTests.size() * Configuration.INSTANCE.getPositiveTestsWeight();
    }
    
    private int indexWithMaxFitness(List<Variant> population) {
        int result = -1;
        for (int i = 0; i < population.size(); i++) {
            if (population.get(i).getFitness() >= getMaxFitness()) {
                result = i;
                break;
            }
        }
        return result;
    }
    
}
