package net.ssehub.program_repair.geneseer.llm;

import java.util.List;

public interface ILlmResponse {

    public List<LlmMessage> getMessages();
    
    public default String getContent() {
        return getMessages().get(0).getContent();
    }
    
    public default String getThinking() {
        return getMessages().get(0).getThinking();
    }
    
    public int getQueryTokens();
    
    public int getAnswerTokens();
    
}
