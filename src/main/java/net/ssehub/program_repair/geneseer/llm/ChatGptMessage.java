package net.ssehub.program_repair.geneseer.llm;

import com.google.gson.annotations.SerializedName;

record ChatGptMessage(String content, Role role) {
    
    public enum Role {
        @SerializedName("system") SYSTEM,
        @SerializedName("user") USER,
        @SerializedName("assistant") ASSISTANT,
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ChatGptMessage[content=");
        String content = this.content;
        if (content.length() > 20) {
            content = content.substring(0, 20) + "...";
        }
        builder.append(content.replaceAll("\r?\n", "\\\\n"));
        builder.append(", role=");
        builder.append(role);
        builder.append("]");
        return builder.toString();
    }
    
}
