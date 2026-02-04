package net.ssehub.program_repair.geneseer.fixers.genetic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;

class Variant {

    private static int idCounter = 0;
    
    private final String name;
    
    private List<String> mutations = new LinkedList<>();
    
    private Node ast;
    
    private Double fitness;
    
    private List<TestResult> failingTests;
    
    public Variant(Node ast) {
        this.name = "V_" + String.format(Locale.ROOT, "%06d", idCounter++);
        this.ast = ast;
    }
    
    private Variant(Variant copy) {
        this.name = copy.name;
        this.mutations.addAll(copy.mutations);
        this.ast = copy.ast;
        this.fitness = copy.fitness;
        this.failingTests = new ArrayList<>(copy.failingTests);
    }

    public Node getAst() {
        return ast;
    }
    
    public void setAst(Node ast) {
        this.ast = ast;
        this.fitness = null;
    }
    
    public boolean hasFitness() {
        return fitness != null;
    }
    
    public double getFitness() {
        return fitness;
    }
    
    public void setFitness(double fitness, List<TestResult> failingTests) {
        this.fitness = fitness;
        this.failingTests = failingTests;
    }
    
    public List<TestResult> getFailingTests() {
        return Collections.unmodifiableList(failingTests);
    }
    
    public String getName() {
        return name;
    }
    
    public void addMutation(String mutationDescription) {
        this.mutations.add(mutationDescription);
    }
    
    public List<String> getMutations() {
        return Collections.unmodifiableList(mutations);
    }
    
    public Variant copy() {
        return new Variant(this);
    }
    
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder(name);
        str.append('{');
        if (fitness != null) {
            str.append("fitness: ").append(fitness).append(", ");
        }
        str.append("mutations:");
        if (mutations.isEmpty()) {
            str.append(" <none>");
        } else {
            mutations.stream()
                    .forEach(m -> str.append(" [").append(m).append(']'));
        }
        str.append('}');
        return str.toString();
    }
    
}
