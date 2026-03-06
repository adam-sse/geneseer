package net.ssehub.program_repair.geneseer.llm.openai;

import java.util.List;

import com.google.gson.annotations.SerializedName;

import net.ssehub.program_repair.geneseer.llm.ILlmResponse;
import net.ssehub.program_repair.geneseer.llm.LlmMessage;

record OpenaiResponse(
        String id,
        List<Choice> choices,
        long created,
        String model,
        @SerializedName("system_fingerprint") String systemFingerprint,
        String object,
        Usage usage) implements ILlmResponse {

    record Choice(
            @SerializedName("finish_reason") FinishReason finishReason,
            int index,
            LlmMessage message,
            Object logprobs) {
    }
    
    enum FinishReason {
        @SerializedName("stop") STOP,
        @SerializedName("length") LENGTH,
        @SerializedName("content_filter") CONTENT_FILTER,
    }
    
    record Usage(
            @SerializedName("completion_tokens") int completionTokens,
            @SerializedName("prompt_tokens") int promptTokens,
            @SerializedName("total_tokens") int totalTokens) {
    }
    
    @Override
    public List<LlmMessage> getMessages() {
        return choices.stream().map(Choice::message).toList();
    }
    
}
