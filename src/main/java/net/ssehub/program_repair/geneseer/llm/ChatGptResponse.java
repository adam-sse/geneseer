package net.ssehub.program_repair.geneseer.llm;

import java.util.List;

import com.google.gson.annotations.SerializedName;

public record ChatGptResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage,
        @SerializedName("system_fingerprint") String systemFingerprint) {

    public record Choice(
            int index,
            ChatGptMessage message,
            Object logprobs,
            @SerializedName("finish_reason") String finishReason) {
    }
    
    public record Usage(
            @SerializedName("prompt_tokens") int promptTokens,
            @SerializedName("completion_tokens") int completionTokens,
            @SerializedName("total_tokens") int totalTokens) {
    }
}
