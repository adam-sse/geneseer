package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;

public interface ILlm {

    public ILlmResponse send(LlmQuery query) throws IOException;
    
}
