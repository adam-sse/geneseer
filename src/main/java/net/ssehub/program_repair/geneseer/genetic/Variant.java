package net.ssehub.program_repair.geneseer.genetic;

import net.ssehub.program_repair.geneseer.parsing.CodeModel;

public class Variant {

    private CodeModel model;
    
    private Double fitness;
    
    public Variant(CodeModel model) {
        this.model = model;
    }

    public CodeModel getCodeModel() {
        return model;
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
