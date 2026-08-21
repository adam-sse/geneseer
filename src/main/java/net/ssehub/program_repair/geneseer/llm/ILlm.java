package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;

public interface ILlm {

    public String getName();
    
    public IResponse send(Query query) throws IOException;
    
}
