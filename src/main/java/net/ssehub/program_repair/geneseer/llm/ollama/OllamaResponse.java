package net.ssehub.program_repair.geneseer.llm.ollama;

import java.util.List;

import net.ssehub.program_repair.geneseer.llm.IResponse;
import net.ssehub.program_repair.geneseer.llm.Message;

record OllamaResponse(
        String model,
        String createdAt,
        Message message,
        boolean done,
        String doneReason,
        long totalDuration,
        long loadDuration,
        int promptEvalCount,
        long promptEvalDuration,
        int evalCount,
        long evalDuration
) implements IResponse {

    @Override
    public List<Message> getMessages() {
        return List.of(message());
    }
    
    @Override
    public int getQueryTokens() {
        return promptEvalCount;
    }
    
    @Override
    public int getAnswerTokens() {
        return evalCount;
    }

}
