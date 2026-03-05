package net.ssehub.program_repair.geneseer.llm.ollama;

import java.util.List;

import com.google.gson.annotations.SerializedName;

import net.ssehub.program_repair.geneseer.llm.ILlmResponse;
import net.ssehub.program_repair.geneseer.llm.LlmMessage;

record OllamaResponse(
        String model,
        @SerializedName("created_at") String createdAt,
        LlmMessage message,
        boolean done,
        @SerializedName("done_reason") String doneReason,
        @SerializedName("total_duration") long totalDuration,
        @SerializedName("load_duration") long loadDuration,
        @SerializedName("prompt_eval_count") long promptEvalCount,
        @SerializedName("prompt_eval_duration") long promptEvalDuration,
        @SerializedName("eval_count") long evalCount,
        @SerializedName("eval_duration") long evalDuration
) implements ILlmResponse {

    @Override
    public List<LlmMessage> getMessages() {
        return List.of(message());
    }

}
