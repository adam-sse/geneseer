package net.ssehub.program_repair.geneseer.llm;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Query {

    private List<Message> messages;
    
    private Long seed;
    
    private Map<String, ?> jsonSchema;
    
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
    
    public void setJsonSchema(Map<String, ?> jsonSchema) {
        this.jsonSchema = jsonSchema;
    }
    
    public Map<String, ?> getJsonSchema() {
        return jsonSchema;
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
        if (jsonSchema != null) {
            builder.append(", jsonSchema=");
            builder.append(jsonSchema);
        }
        builder.append("]");
        return builder.toString();
    }
    
}
