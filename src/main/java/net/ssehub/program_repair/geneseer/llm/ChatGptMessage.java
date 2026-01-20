package net.ssehub.program_repair.geneseer.llm;

import java.util.Objects;

import com.google.gson.annotations.SerializedName;

public class ChatGptMessage {
    
    public enum Role {
        @SerializedName("system") SYSTEM,
        @SerializedName("user") USER,
        @SerializedName("assistant") ASSISTANT,
    }
    
    private String content;
    
    private Role role;
    
    public ChatGptMessage(String content, Role role) {
        this.content = content;
        this.role = role;
    }
    
    public String getContent() {
        return content;
    }
    
    void setContent(String content) {
        this.content = content;
    }
    
    public Role getRole() {
        return role;
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

    @Override
    public int hashCode() {
        return Objects.hash(content, role);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ChatGptMessage)) {
            return false;
        }
        ChatGptMessage other = (ChatGptMessage) obj;
        return Objects.equals(content, other.content) && role == other.role;
    }
    
}
