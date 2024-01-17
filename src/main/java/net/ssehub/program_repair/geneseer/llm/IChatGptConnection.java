package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;

public interface IChatGptConnection {

    public ChatGptResponse send(ChatGptRequest request) throws IOException;
    
}
