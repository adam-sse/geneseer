package net.ssehub.program_repair.geneseer.llm;

import java.util.Objects;

public class Message {
    
    private String content;
    
    private String thinking;
    
    private Role role;
    
    public Message(Role role, String content) {
        this.role = role;
        this.content = content;
    }
    
    public Role getRole() {
        return role;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public String getThinking() {
        return thinking;
    }
    
    public void setThinking(String thinking) {
        this.thinking = thinking;
    }
    
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Message[role=");
        builder.append(role);
        builder.append(", content=");
        String content = this.content;
        if (content.length() > 20) {
            content = content.substring(0, 20) + "...";
        }
        builder.append(content.replaceAll("\r?\n", "\\\\n"));
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
        if (!(obj instanceof Message)) {
            return false;
        }
        Message other = (Message) obj;
        return Objects.equals(content, other.content) && role == other.role;
    }
    
}
