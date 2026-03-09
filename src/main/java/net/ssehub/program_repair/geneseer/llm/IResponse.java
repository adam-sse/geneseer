package net.ssehub.program_repair.geneseer.llm;

import java.util.List;

public interface IResponse {

    public List<Message> getMessages();
    
    public default String getContent() {
        return getMessages().get(0).getContent();
    }
    
    public default String getThinking() {
        return getMessages().get(0).getThinking();
    }
    
    public int getQueryTokens();
    
    public int getAnswerTokens();
    
}
