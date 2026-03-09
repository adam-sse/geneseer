package net.ssehub.program_repair.geneseer.llm;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class Query {

    private List<Message> messages;
    
    private Long seed;
    
    public Query() {
        messages = new LinkedList<>();
    }
    
    public void addMessage(Message message) {
        this.messages.add(message);
    }
    
    public List<Message> getMessages() {
        return Collections.unmodifiableList(messages);
    }
    
    public void setSeed(long seed) {
        this.seed = seed;
    }
    
    public Long getSeed() {
        return seed;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Query[messages=");
        builder.append(messages);
        if (seed != null) {
            builder.append(", seed=");
            builder.append(seed);
        }
        builder.append("]");
        return builder.toString();
    }
    
}
