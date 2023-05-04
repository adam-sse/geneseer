package net.ssehub.program_repair.geneseer.mutations;

import spoon.reflect.CtModel;

public interface IMutation {

    public void apply(CtModel model) throws MutationException;
 
    public String textDescription();
    
}
