package net.ssehub.program_repair.geneseer.genetic;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import net.ssehub.program_repair.geneseer.parsing.model.Node;

public class Variant {

    private static int idCounter = 0;
    
    private final String name;
    
    private List<String> mutations = new LinkedList<>();
    
    private Node ast;
    
    private Double fitness;
    
    public Variant(Node ast) {
        this.name = "V_" + idCounter++;
        this.ast = ast;
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
    
    public void setFitness(double fitness) {
        this.fitness = fitness;
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
