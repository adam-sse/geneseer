package net.ssehub.program_repair.geneseer.llm;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class LlmQuery {

    private String model;
    
    private List<LlmMessage> messages;
    
    private Double temperature;
    
    private Long seed;
    
    public LlmQuery(String model) {
        this.model = model;
        messages = new LinkedList<>();
    }
    
    public String getModel() {
        return model;
    }

    public void addMessage(LlmMessage message) {
        this.messages.add(message);
    }
    
    public List<LlmMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }
    
    public void setTemperature(double temperature) {
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
        this.temperature = temperature;
    }
    
    public Double getTemperature() {
        return temperature;
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
        builder.append("LlmQuery[model=");
        builder.append(model);
        builder.append(", messages=");
        builder.append(messages);
        builder.append(", temperature=");
        builder.append(temperature);
        builder.append(", seed=");
        builder.append(seed);
        builder.append("]");
        return builder.toString();
    }
    
}
