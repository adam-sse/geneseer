package net.ssehub.program_repair.geneseer.genetic;

import net.ssehub.program_repair.geneseer.parsing.model.Node;

public class Variant {

    private Node ast;
    
    private Double fitness;
    
    public Variant(Node ast) {
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
    
}
