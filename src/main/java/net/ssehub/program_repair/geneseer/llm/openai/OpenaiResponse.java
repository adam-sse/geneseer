package net.ssehub.program_repair.geneseer.llm.openai;

import java.util.List;

import com.google.gson.annotations.SerializedName;

import net.ssehub.program_repair.geneseer.llm.Message;

record OpenaiResponse(
        String id,
        List<Choice> choices,
        String model,
        String object,
        Usage usage) {

    record Choice(
            FinishReason finishReason,
            int index,
            Message delta) {
    }
    
    enum FinishReason {
        @SerializedName("stop") STOP,
        @SerializedName("length") LENGTH,
        @SerializedName("tool_calls") TOOL_CALLS,
        @SerializedName("content_filter") CONTENT_FILTER,
        @SerializedName("function_call") FUNCTION_CALL,
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
    
}
