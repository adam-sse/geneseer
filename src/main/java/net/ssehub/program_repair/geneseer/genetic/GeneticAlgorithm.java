package net.ssehub.program_repair.geneseer.genetic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Configuration.GeneticConfiguration.MutationScope;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.Evaluator;
import net.ssehub.program_repair.geneseer.evaluation.fault_localization.FaultLocalization;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.parsing.model.Position;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class GeneticAlgorithm {

    private static final Logger LOG = Logger.getLogger(GeneticAlgorithm.class.getName());
    
    private Random random = new Random(Configuration.INSTANCE.genetic().randomSeed());
    
    private Project project;
    
    private Evaluator evaluator;
    private FitnessEvaluator fitnessEvaluator;
    
    private LlmFixer llmFixer;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private int generation;
    private Variant unmodifiedVariant;
    
    private int numInsertions;
    private int numDeletions;
    private int numSuccessfulCrossovers;
    private int numFailedCrossovers;
    private int numLlmCalls;
    private int numLlmCallsOnUnmodified;
    private int numLlmCallsWithPreviousModifications;
    
    public GeneticAlgorithm(Project project, Evaluator evaluator, LlmFixer llmfixer,
            TemporaryDirectoryManager tempDirManager) {
        this.evaluator = evaluator;
        this.project = project;
        this.llmFixer = llmfixer;
        this.tempDirManager = tempDirManager;
    }
    
    public void run(Map<String, Object> result) throws IOException {
        try (Probe measure = Measurement.INSTANCE.start("genetic-algorithm")) {
            runInternal(result);
        } catch (IOException e) {
            result.put("generation", generation);
            throw e;
        }
    }
    
    void setRandom(Random random) {
        this.random = random;
    }
    
    private void runInternal(Map<String, Object> result) throws IOException {
        LOG.info("Parsing code");
        createUnmodifiedVariant();
        Map<String, Object> astStats = new HashMap<>();
        result.put("ast", astStats);
        astStats.put("nodes", unmodifiedVariant.getAst().stream().count());
        
        try {
            fitnessEvaluator = new FitnessEvaluator(unmodifiedVariant, evaluator,
                    new FaultLocalization(project.getProjectDirectory(), project.getTestExecutionClassPathAbsolute(),
                            project.getEncoding()), tempDirManager);
            
            Map<String, Object> fitnessResult = new HashMap<>();
            result.put("fitness", fitnessResult);
            fitnessResult.put("original", unmodifiedVariant.getFitness());
            fitnessResult.put("max", fitnessEvaluator.getMaxFitness());
            astStats.put("suspicious", unmodifiedVariant.getAst().stream()
                    .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                    .count());
            
            LOG.info("Creating initial population");
            List<Variant> population = createInitialPopulation();
            
            while (!fitnessEvaluator.hasFoundMaxFitness()
                    && ++generation <= Configuration.INSTANCE.genetic().generationLimit()) {
                singleGeneration(population);
            }
            
            Map<String, Integer> evaluationStats = new HashMap<>();
            evaluationStats.put("compilations", evaluator.getNumCompilations());
            evaluationStats.put("testSuiteRuns", evaluator.getNumTestSuiteRuns());
            
            result.put("evaluations", evaluationStats);
            
            Map<String, Integer> mutationStats = new HashMap<>();
            mutationStats.put("insertions", numInsertions);
            mutationStats.put("deletions", numDeletions);
            mutationStats.put("successfulCrossovers", numSuccessfulCrossovers);
            mutationStats.put("failedCrossovers", numFailedCrossovers);
            mutationStats.put("llmCalls", numLlmCalls);
            mutationStats.put("llmCallsOnUnmodified", numLlmCallsOnUnmodified);
            mutationStats.put("llmCallsWithPreviousModifications", numLlmCallsWithPreviousModifications);
            
            result.put("mutationStats", mutationStats);
            result.put("generation", generation);
            fitnessResult.put("best", fitnessEvaluator.getBestVariant().getFitness());
            
            if (fitnessEvaluator.hasFoundMaxFitness()) {
                LOG.info(() -> "Variant with max fitness: " + fitnessEvaluator.getBestVariant());
                result.put("result", "FOUND_FIX");
            } else {
                LOG.info(() -> "Stopping because limit of " + Configuration.INSTANCE.genetic().generationLimit()
                        + " generations reached");
                result.put("result", "GENERATION_LIMIT_REACHED");
            }
            
            try {
                analyzeDiffOfBestVariant(result, fitnessEvaluator.getBestVariant());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to compute diff of best variant", e);
            }
        } catch (EvaluationException e) {
            LOG.log(Level.SEVERE, "Failed evaluation of unmodified original", e);
            result.put("result", "ORIGINAL_UNFIT");
        }
        
    }

    private void createUnmodifiedVariant() throws IOException {
        Node ast = Parser.parse(project.getSourceDirectoryAbsolute(), project.getEncoding());
        ast.lock();
        LOG.fine(() -> ast.stream().count() + " nodes in AST");
        
        this.unmodifiedVariant = new Variant(ast);
    }

    private List<Variant> createInitialPopulation() throws IOException {
        List<Variant> population = new ArrayList<>(Configuration.INSTANCE.genetic().populationSize());
        for (int i = 0; i < Configuration.INSTANCE.genetic().populationSize(); i++) {
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
        LOG.info(() -> "Viable: " + viable.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
        
        List<Double> cummulativeProbabilityDistribution = calculateCummulativeProbabilityDistribution(viable);
        
        List<Integer> selected;
        if (viable.size() > Configuration.INSTANCE.genetic().populationSize() / 2) {
            selected = stochasticUniversalSampling(cummulativeProbabilityDistribution,
                    Configuration.INSTANCE.genetic().populationSize() / 2);
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
        
        while (population.size() < Configuration.INSTANCE.genetic().populationSize()) {
            population.add(newVariant());
        }
        
        for (Variant variant : population) {
            mutate(variant);
        }
        LOG.info(() -> "Population fitness: " + population.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
    }

    private Variant newVariant() throws IOException {
        Variant variant = new Variant(unmodifiedVariant.getAst());
        variant.setFitness(unmodifiedVariant.getFitness(), unmodifiedVariant.getFailingTests());
        LOG.fine("Creating new variant " + variant.getName());
        mutate(variant);
        return variant;
    }
    
    private void mutate(Variant variant) throws IOException {
        boolean needsFitnessReevaluation = false;
        boolean needsFaultLocalization = false;
        
        Node astRoot = variant.getAst();
        
        if (random.nextDouble() < Configuration.INSTANCE.genetic().llmMutationProbability()) {
            LOG.info(() -> "Using LLM to mutate variant " + variant.getName());
            if (!variant.hasFitness()) {
                LOG.fine(() -> "Running tests on variant as it has no fitness and thus no failing tests yet");
                fitnessEvaluator.measureFitness(variant, false);
            }
            
            try (Probe measure = Measurement.INSTANCE.start("llm-mutation")) {
                numLlmCalls++;
                if (variant.getMutations().isEmpty()) {
                    numLlmCallsOnUnmodified++;
                } else {
                    numLlmCallsWithPreviousModifications++;
                }
                Optional<Node> result = llmFixer.createVariant(astRoot, variant.getFailingTests());
                if (result.isPresent()) {
                    astRoot = result.get();
                    variant.setAst(astRoot);
                    variant.addMutation("LLM");
                    needsFitnessReevaluation = true;
                    needsFaultLocalization = true;
                } else {
                    LOG.fine(() -> "Got no (usable) result from LLM...");
                }
            }
            
        } else {
            List<Node> suspiciousStatements = astRoot.stream()
                    .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                    .toList();
            
            double mutationProbability =
                    Configuration.INSTANCE.genetic().mutationProbability() / suspiciousStatements.size();
            List<Node> ss = suspiciousStatements;
            LOG.finer(() -> ss.size() + " suspicious statements -> mutation probability " + mutationProbability);
            for (int i = 0; i < suspiciousStatements.size(); i++) {
                Node suspicious = suspiciousStatements.get(i);
                if (random.nextDouble() < mutationProbability 
                        && random.nextDouble() < (double) suspicious.getMetadata(Metadata.SUSPICIOUSNESS)) {
                    
                    astRoot = singleMutation(variant, astRoot, suspicious);
                    
                    suspiciousStatements = astRoot.stream()
                            .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                            .toList();
                    
                    needsFitnessReevaluation = true;
                }
            }
        }
        
        if (!needsFitnessReevaluation && !needsFaultLocalization) {
            LOG.fine(() -> "No new mutations added to " + variant.getName());
        }
        
        if (needsFitnessReevaluation || !variant.hasFitness()) {
            LOG.fine(() -> "Evaluating fitness after mutation");
            fitnessEvaluator.measureFitness(variant, needsFaultLocalization);
        }
    }
    
    private static void setSamePosition(Node from, Node to) {
        Node firstLeaf = from;
        while (firstLeaf.getType() != Type.LEAF) {
            firstLeaf = firstLeaf.get(0);
        }
        
        Position position = ((LeafNode) firstLeaf).getPosition();
        to.stream()
                .filter(n -> n.getType() == Type.LEAF)
                .forEach(n -> ((LeafNode) n).setPosition(position));
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
                numDeletions++;
            }
            
        } else {
            Node otherStatement = selectOtherStatement(astRoot, suspicious).clone();
            setSamePosition(suspicious, otherStatement);
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
                numInsertions++;
                
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
        if (Configuration.INSTANCE.genetic().statementScope() == MutationScope.FILE) {
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
        
        List<Variant> result;
        if (p1Parents.size() == 0) {
            LOG.fine(() -> "Parents don't differ, crossover not possible");
            numFailedCrossovers++;
            result = List.of();
        } else {
            numSuccessfulCrossovers++;
            result = applyCrossover(p1, p2, p1Parents, p2Parents);
        }
        return result;
    }

    private List<Variant> applyCrossover(Variant p1, Variant p2, List<Node> p1Parents, List<Node> p2Parents) {
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
            newC1Parent.copyMetadataFromNode(c1Parent);
            Node newC2Parent = new InnerNode(c2Parent.getType());
            newC2Parent.copyMetadataFromNode(c1Parent);
            
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
    
    private void analyzeDiffOfBestVariant(Map<String, Object> result, Variant bestVariant) throws IOException {
        Map<String, Object> diffResult = new HashMap<>();
        result.put("patch", diffResult);
        
        diffResult.put("mutations", bestVariant.getMutations());
        
        String diff = AstDiff.getDiff(unmodifiedVariant.getAst(), bestVariant.getAst(), tempDirManager,
                project.getEncoding());
        
        LOG.info(() -> "Best variant " + bestVariant + ":\n" + diff);
        diffResult.put("diff", diff);
        
        String[] difflines = diff.split("\n");
        int i;
        for (i = 0; i < difflines.length; i++) {
            if (difflines[i].startsWith("@@")) {
                break;
            }
        }
        
        int countAdd = 0;
        int countRemove = 0;
        for (; i < difflines.length; i++) {
            String l = difflines[i];
            if (l.startsWith("+") && !l.substring(1).isBlank()) {
                countAdd++;
            } else if (l.startsWith("-") && !l.substring(1).isBlank()) {
                countRemove++;
            } else if (l.startsWith("diff --git ")) {
                for (; i < difflines.length; i++) {
                    if (difflines[i].startsWith("@@")) {
                        break;
                    }
                }
            }
        }
        diffResult.put("addedLines", countAdd);
        diffResult.put("removedLines", countRemove);
    }
    
}
