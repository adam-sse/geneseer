package net.ssehub.program_repair.geneseer.llm.openai;

import java.util.List;

import com.google.gson.annotations.SerializedName;

import net.ssehub.program_repair.geneseer.llm.ILlmResponse;
import net.ssehub.program_repair.geneseer.llm.LlmMessage;

record OpenaiResponse(
        String id,
        List<Choice> choices,
        String model,
        String systemFingerprint,
        String object,
        Usage usage) implements ILlmResponse {

    record Choice(
            FinishReason finishReason,
            int index,
            LlmMessage message) {
    }
    
    enum FinishReason {
        @SerializedName("stop") STOP,
        @SerializedName("length") LENGTH,
        @SerializedName("content_filter") CONTENT_FILTER,
    }
    
    record Usage(
            int completionTokens,
            int promptTokens,
            int totalTokens,
            UsageDetails completionTokensDetails) {
        
        @Override
        public final String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("query tokens: ").append(promptTokens);
            sb.append(", answer tokens: ").append(completionTokens);
            if (completionTokensDetails != null && completionTokensDetails.reasoningTokens != null) {
                sb.append(" (").append(completionTokensDetails.reasoningTokens).append(" thinking)");
            }
            return sb.toString();
        }
        
    }
    
    record UsageDetails(Integer reasoningTokens) {
    }
    
    @Override
    public List<LlmMessage> getMessages() {
        return choices.stream().map(Choice::message).toList();
    }
    
    @Override
    public int getQueryTokens() {
        return usage != null ? usage.promptTokens : 0;
    }
    
    @Override
    public int getAnswerTokens() {
        return usage != null ? usage.completionTokens : 0;
    }
    
}
