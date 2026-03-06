package net.ssehub.program_repair.geneseer.llm.ollama;

import java.util.List;

import net.ssehub.program_repair.geneseer.llm.ILlmResponse;
import net.ssehub.program_repair.geneseer.llm.LlmMessage;

record OllamaResponse(
        String model,
        String createdAt,
        LlmMessage message,
        boolean done,
        String doneReason,
        long totalDuration,
        long loadDuration,
        int promptEvalCount,
        long promptEvalDuration,
        int evalCount,
        long evalDuration
) implements ILlmResponse {

    @Override
    public List<LlmMessage> getMessages() {
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
