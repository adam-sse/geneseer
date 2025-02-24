package net.ssehub.program_repair.geneseer.fixers.genetic;

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
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.fixers.IFixer;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.parsing.model.Position;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class GeneticAlgorithm implements IFixer {

    private static final Logger LOG = Logger.getLogger(GeneticAlgorithm.class.getName());
    
    private Random random = new Random(Configuration.INSTANCE.genetic().randomSeed());
    
    private FitnessEvaluator fitnessEvaluator;
    
    private LlmFixer llmFixer;
    
    private int generation;
    private Variant unmodifiedVariant;
    
    private int numInsertions;
    private int numDeletions;
    private int numFailedMutations;
    private int numSuccessfulCrossovers;
    private int numFailedCrossovers;
    private int numLlmCallsOnUnmodified;
    private int numLlmCallsOnMutated;
    private int numUnusableLlmAnswers;
    
    public GeneticAlgorithm(LlmFixer llmfixer) {
        this.llmFixer = llmfixer;
    }
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) {
        try (Probe measure = Measurement.INSTANCE.start("genetic-algorithm")) {
            this.unmodifiedVariant = new Variant(ast);
            this.fitnessEvaluator = new FitnessEvaluator(testSuite, this.unmodifiedVariant);
            
            return runInternal(result);
        }
    }
    
    void setRandom(Random random) {
        this.random = random;
    }
    
    private Node runInternal(Map<String, Object> result) {
        Map<String, Object> fitnessResult = new HashMap<>();
        result.put("fitness", fitnessResult);
        fitnessResult.put("original", unmodifiedVariant.getFitness());
        fitnessResult.put("max", fitnessEvaluator.getMaxFitness());
        
        List<Variant> population = createInitialPopulation();
        
        while (!fitnessEvaluator.hasFoundMaxFitness()
                && ++generation <= Configuration.INSTANCE.genetic().generationLimit()) {
            singleGeneration(population);
        }
        
        Map<String, Integer> mutationStats = new HashMap<>();
        mutationStats.put("insertions", numInsertions);
        mutationStats.put("deletions", numDeletions);
        mutationStats.put("failedMutations", numFailedMutations);
        mutationStats.put("successfulCrossovers", numSuccessfulCrossovers);
        mutationStats.put("failedCrossovers", numFailedCrossovers);
        mutationStats.put("llmCallsOnUnmodified", numLlmCallsOnUnmodified);
        mutationStats.put("llmCallsOnMutated", numLlmCallsOnMutated);
        mutationStats.put("unusableLlmAnswers", numUnusableLlmAnswers);
        
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

        Map<String, Object> diffResult = new HashMap<>();
        result.put("patch", diffResult);
        diffResult.put("mutations", fitnessEvaluator.getBestVariant().getMutations());
        
        return fitnessEvaluator.getBestVariant().getAst();
    }

    private List<Variant> createInitialPopulation() {
        LOG.info("Creating initial population of  " + Configuration.INSTANCE.genetic().populationSize() + " variants");
        List<Variant> population = new ArrayList<>(Configuration.INSTANCE.genetic().populationSize());
        for (int i = 0; i < Configuration.INSTANCE.genetic().populationSize(); i++) {
            population.add(newVariant(i > 0));
            if (fitnessEvaluator.hasFoundMaxFitness()) {
                LOG.info("Skipping rest of initial population creation because variant with maximum fitness has been"
                        + " found");
                break;
            }
        }
        LOG.info(() -> "Population fitness: " + population.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
        return population;
    }

    private void singleGeneration(List<Variant> population) {
        LOG.info(() -> "Generation " + generation);
        
        List<Variant> viable = population.stream()
                .filter(v -> v.getFitness() > 0.0)
                .sorted(Comparator.comparingDouble(Variant::getFitness).reversed())
                .toList();
        LOG.info(() -> viable.size() + " viable: " + viable.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
        
        population.clear();
        reproduction(viable, population);
        
        if (!fitnessEvaluator.hasFoundMaxFitness()) {
            fillPopulation(population);
        }

        if (!fitnessEvaluator.hasFoundMaxFitness()) {
            mutatePopulation(population);
        }
        
        LOG.info(() -> "Population fitness: " + population.stream()
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
    }

    private void reproduction(List<Variant> viable, List<Variant> newPopulation) {
        List<Double> cummulativeProbabilityDistribution = calculateCummulativeProbabilityDistribution(viable.stream()
                .map(Variant::getFitness)
                .toList());
        
        List<Integer> selected;
        if (viable.size() > Configuration.INSTANCE.genetic().populationSize() / 2) {
            selected = stochasticUniversalSampling(cummulativeProbabilityDistribution,
                    Configuration.INSTANCE.genetic().populationSize() / 2);
        } else {
            selected = IntStream.range(0, viable.size()).mapToObj(Integer::valueOf).toList();
        }
        LOG.info(() -> "Selected " + selected.size() + " for reproduction: " + selected.stream()
                .map(i -> viable.get(i))
                .map(v -> v.getName() + "(" + v.getFitness() + ")")
                .toList());
        
        for (int i = 0; i < selected.size(); i += 2) {
            Variant p1 = viable.get(i);
            
            if (i + 1 < viable.size()) {
                Variant p2 = viable.get(i + 1);
                
                List<Variant> children = crossover(p1, p2);
                
                newPopulation.add(p1);
                newPopulation.add(p2);
                newPopulation.addAll(children);
                
            } else {
                newPopulation.add(p1);
            }
            
            if (fitnessEvaluator.hasFoundMaxFitness()) {
                LOG.info("Skipping rest of reproduction because variant with maximum fitness has been found");
                break;
            }
        }
    }

    private void fillPopulation(List<Variant> population) {
        LOG.info(() -> "Filling up population with "
                + (Configuration.INSTANCE.genetic().populationSize() - population.size()) + " new variants");
        while (population.size() < Configuration.INSTANCE.genetic().populationSize()) {
            population.add(newVariant(true));
            if (fitnessEvaluator.hasFoundMaxFitness()) {
                LOG.info("Skipping further population filling because variant with maximum fitness has been found");
                break;
            }
        }
    }

    private void mutatePopulation(List<Variant> population) {
        LOG.info("Mutating population");
        for (Variant variant : population) {
            if (random.nextDouble() < Configuration.INSTANCE.genetic().mutationProbability()) {
                mutate(variant);
                LOG.info(() -> "Mutated " + variant);
                if (fitnessEvaluator.hasFoundMaxFitness()) {
                    LOG.info("Skipping mutating rest of population because variant with maximum fitness has been"
                            + " found");
                    break;
                }
            }
        }
    }

    private Variant newVariant(boolean withMutation) {
        Variant variant = new Variant(unmodifiedVariant.getAst());
        if (withMutation) {
            mutate(variant);
        } else {
            variant.setFitness(unmodifiedVariant.getFitness(), unmodifiedVariant.getFailingTests());
        }
        LOG.info("Created new " + variant);
        return variant;
    }
    
    private void mutate(Variant variant) {
        boolean needsFitnessReevaluation = false;
        boolean needsFaultLocalization = false;
        
        Node astRoot = variant.getAst();
        List<Node> suspiciousStatements = astRoot.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .sorted(Node.DESCENDING_SUSPICIOUSNESS)
                .toList();
        
        if (!suspiciousStatements.isEmpty()) {
            if (random.nextDouble() < Configuration.INSTANCE.genetic().llmMutationProbability()) {
                LOG.info(() -> "Using LLM to mutate " + variant.getName());
                if (!variant.hasFitness()) {
                    LOG.fine(() -> "Running tests on variant as it has no fitness and thus no failing tests yet");
                    fitnessEvaluator.measureFitness(variant, false);
                }
                
                try (Probe measure = Measurement.INSTANCE.start("llm-mutation")) {
                    if (variant.getMutations().isEmpty()) {
                        numLlmCallsOnUnmodified++;
                    } else {
                        numLlmCallsOnMutated++;
                    }
                    try {
                        Optional<Node> result = llmFixer.createVariant(astRoot, variant.getFailingTests());
                        if (result.isPresent()) {
                            astRoot = result.get();
                            variant.setAst(astRoot);
                            variant.addMutation("LLM");
                            needsFitnessReevaluation = true;
                            needsFaultLocalization = true;
                        } else {
                            numUnusableLlmAnswers++;
                            LOG.info(() -> "Got no usable result from LLM");
                        }
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "IOException while querying LLM", e);
                        numUnusableLlmAnswers++;
                    }
                }
                
            } else {
                List<Double> probabilities = calculateCummulativeProbabilityDistribution(suspiciousStatements.stream()
                        .map(s -> (Double) s.getMetadata(Metadata.SUSPICIOUSNESS))
                        .toList());
                
                double randomValue = random.nextDouble();
                int selected = 0;
                while (randomValue > probabilities.get(selected) && selected < suspiciousStatements.size()) {
                    selected++;
                }
                
                Node suspicious = suspiciousStatements.get(selected);
                needsFitnessReevaluation = singleMutation(variant, astRoot, suspicious);
            }
        } else {
            numFailedMutations++;
            LOG.warning(() -> "Failed to muate " + variant.getName() + " because it has no suspicious statements");
        }
        
        if (!needsFitnessReevaluation) {
            LOG.info(() -> "No new mutation added to " + variant.getName());
        }
        
        if (needsFitnessReevaluation || !variant.hasFitness()) {
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

    private boolean singleMutation(Variant variant, Node astRoot, Node suspicious) {
        boolean success;
        Node oldAstRoot = astRoot;
        astRoot = astRoot.cheapClone(suspicious);
        variant.setAst(astRoot);
        suspicious = astRoot.findEquivalentPath(oldAstRoot, suspicious);
        Node parent = astRoot.findParent(suspicious).get();
        
        int rand = random.nextInt(2); // TODO: no swap yet
        if (rand == 0) {
            // delete
            boolean removed = parent.remove(suspicious);
            if (!removed) {
                Node s = suspicious;
                LOG.warning(() -> "Failed to delete statement " + s.toString());
                success = false;
            } else {
                variant.addMutation("del " + suspicious.toString());
                numDeletions++;
                success = true;
            }
            
        } else {
            Node otherStatement = selectOtherStatement(astRoot, suspicious).clone();
            setSamePosition(suspicious, otherStatement);
            otherStatement.setMetadata(Metadata.SUSPICIOUSNESS,
                    suspicious.getMetadata(Metadata.SUSPICIOUSNESS));
            
            if (rand == 1) {
                // insert
                int index = parent.indexOf(suspicious);
                parent.add(index, otherStatement);
                variant.addMutation("ins " + otherStatement.toString() + " before " + suspicious.toString());
                numInsertions++;
                success = true;
                
            } else {
                // swap
                // TODO
                success = false;
            }
        }
        
        astRoot.lock();
        return success;
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
                .filter(n -> n.getType() == Type.STATEMENT)
                .toList();
        Node otherStatement = allStatements.get(random.nextInt(allStatements.size()));
        return otherStatement;
    }
    
    private List<Variant> crossover(Variant p1, Variant p2) {
        List<Node> p1Parents = new LinkedList<>();
        List<Node> p2Parents = new LinkedList<>();
        findMatchingModifiedBlocks(p1.getAst(), p2.getAst(), p1Parents, p2Parents);
        
        List<Variant> result;
        if (p1Parents.size() == 0) {
            LOG.info(() -> p1 + " and " + p2 + " don't differ, crossover not possible");
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
        fitnessEvaluator.measureFitness(v1, false);
        LOG.info(() -> "Created " + v1 + " as child of " + p1 + " and " + p2);
        
        Variant v2 = new Variant(c2);
        v2.addMutation("child " + p2.getName() + " " + p1.getName());
        fitnessEvaluator.measureFitness(v2, false);
        LOG.info(() -> "Created " + v2 + " as child of " + p2 + " and " + p1);
        
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
    
    private List<Double> calculateCummulativeProbabilityDistribution(List<Double> values) {
        double sum = values.stream().mapToDouble(d -> d).sum();
        
        List<Double> cummulativeProbabilityDistribution = new ArrayList<>(values.size());
        values.stream()
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
    
}
