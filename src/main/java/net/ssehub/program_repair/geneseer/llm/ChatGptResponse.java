package net.ssehub.program_repair.geneseer.llm;

import java.util.List;

import com.google.gson.annotations.SerializedName;

record ChatGptResponse(
        String id,
        List<Choice> choices,
        long created,
        String model,
        @SerializedName("system_fingerprint") String systemFingerprint,
        String object,
        Usage usage) {

    public record Choice(
            @SerializedName("finish_reason") FinishReason finishReason,
            int index,
            ChatGptMessage message,
            Object logprobs) {
    }
    
    public enum FinishReason {
        @SerializedName("stop") STOP,
        @SerializedName("length") LENGTH,
        @SerializedName("content_filter") CONTENT_FILTER,
    }
    
    public record Usage(
            @SerializedName("completion_tokens") int completionTokens,
            @SerializedName("prompt_tokens") int promptTokens,
            @SerializedName("total_tokens") int totalTokens) {
    }
    
    public String getContent() {
        return choices.get(0).message.getContent();
    }

}
