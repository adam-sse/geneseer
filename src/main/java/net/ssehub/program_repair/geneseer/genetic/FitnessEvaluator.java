package net.ssehub.program_repair.geneseer.genetic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.CompilationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationResult;
import net.ssehub.program_repair.geneseer.evaluation.Evaluator;
import net.ssehub.program_repair.geneseer.evaluation.TestExecutionException;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.fault_localization.FaultLocalization;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class FitnessEvaluator {

    private static final Logger LOG = Logger.getLogger(FitnessEvaluator.class.getName());
    
    private Evaluator evaluator;
    private FaultLocalization faultLocalization;
    private TemporaryDirectoryManager tempDirManager;
    
    private List<TestResult> originalTests;
    private Set<String> originalTestNames;
    private Set<String> negativeTestNames;
    private Set<String> positiveTestNames;
    
    private Variant unmodifiedVariant;
    
    private Variant bestVariant;
    
    public FitnessEvaluator(Variant unmodifiedVariant, Evaluator evaluator, FaultLocalization faultLocalization,
            TemporaryDirectoryManager tempDirManager) throws EvaluationException {
        this.unmodifiedVariant = unmodifiedVariant;
        this.evaluator = evaluator;
        this.faultLocalization = faultLocalization;
        this.tempDirManager = tempDirManager;
        
        initializeOriginalTestResult();
    }

    private void initializeOriginalTestResult() throws CompilationException, TestExecutionException {
        LOG.info("Evaluating unmodified variant");
        
        evaluator.setLogCompilerOutput(true);
        evaluator.setKeepBinDirectory(true);
        
        EvaluationResult evaluation = evaluator.evaluate(unmodifiedVariant.getAst());
        Path binDirectory = evaluator.getLastBinDirectory();
        try {
            evaluator.setKeepBinDirectory(false);
            evaluator.setLogCompilerOutput(false); // only log compiler output for unmodified original
            
            originalTests = evaluation.getExecutedTests();
            originalTestNames = originalTests.stream()
                    .map(TestResult::toString)
                    .collect(Collectors.toUnmodifiableSet());
            if (originalTestNames.size() != originalTests.size()) {
                LOG.severe("Found duplicate test names in evaluation result");
                throw new IllegalStateException("Duplicate test names reported");
            }
            negativeTestNames = new HashSet<>();
            positiveTestNames = new HashSet<>();
            for (TestResult test : originalTests) {
                if (test.isFailure()) {
                    negativeTestNames.add(test.toString());
                } else {
                    positiveTestNames.add(test.toString());
                }
            }
            
            LOG.fine(() -> "Negative tests (" + negativeTestNames.size() + "): " + negativeTestNames);
            LOG.fine(() -> "Positive tests (" + positiveTestNames.size() + "): " + positiveTestNames);
            
            unmodifiedVariant.setFitness(getFitness(originalTests), evaluation.getFailures());
            LOG.info(() -> "Fitness of unmodified variant (" + unmodifiedVariant.getName() + ") and max fitness: "
                    + unmodifiedVariant.getFitness() + " / " + getMaxFitness());
            bestVariant = unmodifiedVariant;
            
            faultLocalization.measureAndAnnotateSuspiciousness(unmodifiedVariant.getAst(), binDirectory, originalTests);
        } finally {
            try {
                tempDirManager.deleteTemporaryDirectory(binDirectory);
            } catch (IOException e) {
                // ignore, will be cleaned up later when tempDirManager is closed
            }
        }
    }
    
    private double getFitness(List<TestResult> testResult) {
        int numPassingNegative = 0;
        int numPassingPositive = 0;
        Set<String> missingPositiveTests = new HashSet<>(positiveTestNames);
        
        for (TestResult test : testResult) {
            missingPositiveTests.remove(test.toString());
            if (!test.isFailure()) {
                if (negativeTestNames.contains(test.toString())) {
                    numPassingNegative++;
                } else if (positiveTestNames.contains(test.toString())) {
                    numPassingPositive++;
                } else {
                    LOG.severe(() -> test.toString() + " is neither in positive nor negative test cases");
                    throw new IllegalArgumentException(test.toString() + " is not in known test cases");
                }
            }
        }
        
        // assume that positive tests that weren't run are still positive
        // this works because we only execute the relevant tests that cover the modified file(s)
        numPassingPositive += missingPositiveTests.size();
        
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
    
    private static Map<Path, Node> getFileNodesByPath(Node astRoot) {
        Map<Path, Node> fileNodes = new HashMap<>();
        
        for (Node child : astRoot.childIterator()) {
            fileNodes.put((Path) child.getMetadata(Metadata.FILE_NAME), child);
        }
        
        return fileNodes;
    }
    
    private static Set<Node> computeModifiedFiles(Node oldAst, Node newAst) {
        Map<Path, Node> oldAstFiles = getFileNodesByPath(oldAst);
        Map<Path, Node> newAstFiles = getFileNodesByPath(newAst);
        
        if (!oldAstFiles.keySet().equals(newAstFiles.keySet())) {
            LOG.warning("Files have changed between old and new AST");
        }
        
        Set<Node> modifiedFiles = new HashSet<>();
        for (Path file : newAstFiles.keySet()) {
            Node newFile = newAstFiles.get(file);
            Node oldFile = oldAstFiles.get(file);
            if (oldFile != null) {
                if (!oldFile.equals(newFile)) {
                    modifiedFiles.add(newFile);
                }
            } else {
                modifiedFiles.add(newFile);
            }
        }
        
        return modifiedFiles;
    }
    
    private void checkTestsNamesMatchOriginalExactly(List<TestResult> toCheck) {
        Set<String> toCheckNames = toCheck.stream().map(TestResult::toString).collect(Collectors.toSet());
        
        if (!toCheckNames.equals(originalTestNames)) {
            Set<String> missing = new HashSet<>(originalTestNames);
            missing.removeAll(toCheckNames);
            Set<String> unknown = new HashSet<>(toCheckNames);
            unknown.removeAll(originalTestNames);
            
            LOG.severe(() -> "Current test methods in evaluation result do not match original expected set; missing: "
                    + missing + ", unknown: " + unknown);
            
            throw new IllegalStateException("Current tests don't match original expected set");
        }
    }
    
    private void checkTestsNamesMatchOriginalSubset(List<TestResult> toCheck) {
        Set<String> unknownTests = toCheck.stream()
                .map(TestResult::toString)
                .filter(name -> !originalTestNames.contains(name))
                .collect(Collectors.toSet());

        if (!unknownTests.isEmpty()) {
            LOG.severe(() -> "Current test methods in evaluation result contain unexpected tests: " + unknownTests);
            throw new IllegalStateException("Current tests don't match original expected set");
        }
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
            
            EvaluationResult evaluationResult;
            
            if (withFaultLocalization) {
                evaluator.setKeepBinDirectory(true);
                LOG.fine(() -> "Running all tests because fault localization is required");
                evaluationResult = evaluator.evaluate(variant.getAst());
                checkTestsNamesMatchOriginalExactly(evaluationResult.getExecutedTests());
                
            } else {
                Set<Node> modifiedFiles = computeModifiedFiles(unmodifiedVariant.getAst(), variant.getAst());
                @SuppressWarnings("unchecked")
                Set<String> relevantTests = modifiedFiles.stream()
                        .map(n -> (Set<String>) n.getMetadata(Metadata.COVERED_BY))
                        .flatMap(Set::stream)
                        .collect(Collectors.toSet());
                LOG.fine(() -> "Only running " + relevantTests.size() + " relevant tests: " + relevantTests);
                evaluationResult = evaluator.evaluate(variant.getAst(), new ArrayList<>(relevantTests));
                checkTestsNamesMatchOriginalSubset(evaluationResult.getExecutedTests());
            }
            
            fitness = getFitness(evaluationResult.getExecutedTests());
            failingTests = evaluationResult.getFailures();
            
            if (withFaultLocalization) {
                faultLocalization.measureAndAnnotateSuspiciousness(variant.getAst(), evaluator.getLastBinDirectory(),
                        evaluationResult.getExecutedTests());
                
                try {
                    tempDirManager.deleteTemporaryDirectory(evaluator.getLastBinDirectory());
                } catch (IOException e) {
                    // ignore, will be cleaned up later when tempDirManager is closed
                }
            }
            
        } catch (CompilationException e) {
            fitness = 0;
            
        } catch (TestExecutionException e) {
            LOG.log(Level.WARNING, "Failed running tests on variant", e);
            fitness = 0;
            
        } finally {
            evaluator.setKeepBinDirectory(false);
        }
        
        variant.setFitness(fitness, failingTests);
        
        LOG.fine(() -> variant.toString());
        if (fitness > bestVariant.getFitness()) {
            bestVariant = variant.copy();
            LOG.info(() -> "New best variant: " + variant.getName());
        }
    }
    
}
