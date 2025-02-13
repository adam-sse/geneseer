package net.ssehub.program_repair.geneseer.fixers.genetic;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;

class FitnessEvaluator {

    private static final Logger LOG = Logger.getLogger(FitnessEvaluator.class.getName());
    
    private TestSuite testSuite;
    private Set<String> negativeTestNames;
    private Set<String> positiveTestNames;
    
    private Variant bestVariant;
    
    public FitnessEvaluator(TestSuite testSuite, Variant unmodifiedOriginal) {
        this.testSuite = testSuite;
        this.negativeTestNames = testSuite.getInitialFailingTestResults().stream()
                .map(TestResult::toString)
                .collect(Collectors.toSet());
        this.positiveTestNames = testSuite.getInitialPassingTestResults().stream()
                .map(TestResult::toString)
                .collect(Collectors.toSet());
        this.bestVariant = unmodifiedOriginal;
        
        unmodifiedOriginal.setFitness(getFitness(testSuite.getInitialTestResults()),
                testSuite.getInitialTestResults().stream()
                        .filter(TestResult::isFailure)
                        .toList());
    }

    private double getFitness(Collection<TestResult> testResult) {
        int numPassingNegative = 0;
        int numPassingPositive = 0;
        
        for (TestResult test : testResult) {
            if (!test.isFailure()) {
                if (negativeTestNames.contains(test.toString())) {
                    numPassingNegative++;
                } else if (positiveTestNames.contains(test.toString())) {
                    numPassingPositive++;
                } else {
                    throw new IllegalArgumentException(test.toString()
                            + " is neither in positive nor negative test cases");
                }
            }
        }
        
        double fitness = Configuration.INSTANCE.genetic().negativeTestsWeight() * numPassingNegative
                + Configuration.INSTANCE.genetic().positiveTestsWeight() * numPassingPositive;
        if (fitness > getMaxFitness()) {
            LOG.severe(() -> "Calculated invalid (too high) fitness for evaluation result");
            throw new IllegalArgumentException("Fitness " + fitness + " is greater than max fitness "
                    + getMaxFitness());
        }
        return fitness;
    }
    
    public Variant getBestVariant() {
        return bestVariant;
    }
    
    public boolean hasFoundMaxFitness() {
        return bestVariant.getFitness() == getMaxFitness();
    }
    
    public double getMaxFitness() {
        return negativeTestNames.size() * Configuration.INSTANCE.genetic().negativeTestsWeight()
                + positiveTestNames.size() * Configuration.INSTANCE.genetic().positiveTestsWeight();
    }
    
    public void measureFitness(Variant variant, boolean withFaultLocalization) {
        if (!withFaultLocalization) {
            for (Node classNode : variant.getAst().childIterator()) {
                if (classNode.getMetadata(Metadata.COVERED_BY) == null) {
                    withFaultLocalization = true;
                    LOG.warning(() -> "Evaluating variant without coverage information; forcing fault localization");
                    break;
                }
            }
        }
        
        double fitness;
        List<TestResult> failingTests = List.of();
        try {
            List<TestResult> evaluationResult;
            
            if (withFaultLocalization) {
                evaluationResult = testSuite.runAndAnnotateFaultLocalization(variant.getAst());
            } else {
                evaluationResult = testSuite.evaluate(variant.getAst());
            }
            
            fitness = getFitness(evaluationResult);
            failingTests = evaluationResult.stream().filter(TestResult::isFailure).toList();
            
        } catch (CompilationException e) {
            fitness = 0;
            
        } catch (EvaluationException e) {
            LOG.log(Level.WARNING, "Failed running tests on variant", e);
            fitness = 0;
        }
        
        variant.setFitness(fitness, failingTests);
        
        LOG.fine(() -> "Measured fitness: " + variant.toString());
        if (fitness > bestVariant.getFitness()) {
            bestVariant = variant.copy();
            LOG.info(() -> "New best variant " + variant.getName() + " with fitness " + variant.getFitness());
        }
    }
    
}
