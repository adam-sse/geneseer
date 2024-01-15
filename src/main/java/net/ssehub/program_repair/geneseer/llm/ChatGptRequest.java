package net.ssehub.program_repair.geneseer.llm;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class ChatGptRequest {

    private String model;
    
    private final boolean stream = false;
    
    private List<ChatGptMessage> messages;
    
    public ChatGptRequest(String model) {
        this.model = model;
        messages = new LinkedList<>();
    }
    
    public void addMessage(ChatGptMessage message) {
        this.messages.add(message);
    }
    
    public List<ChatGptMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }
    
    public String getModel() {
        return model;
    }
    
    public boolean isStream() {
        return stream;
    }
    
}
