package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

public class LlmConfiguration {

    public static final LlmConfiguration INSTANCE = new LlmConfiguration();
    
    private static final Logger LOG = Logger.getLogger(LlmConfiguration.class.getName());
    
    private String model = "dummy";
    
    private int maxCodeContext = 100;
    
    private Double temperature = null;
    
    private Long seed = null;
    
    private URL apiUrl;
    
    private String apiToken;
    
    public void loadFromFile(Path file) throws IOException {
        LOG.info(() -> "Loading configuration file " + file.toAbsolutePath());
        Properties properties = new Properties();
        properties.load(Files.newBufferedReader(file));
        
        for (Object key : properties.keySet()) {
            String value = properties.getProperty((String) key);
            
            switch ((String) key) {
            case "llm.model":
                model = value;
                break;
                
            case "llm.maxCodeContext":
                maxCodeContext = Integer.parseInt(value);
                break;
                
            case "llm.temperature":
                temperature = Double.parseDouble(value);
                break;
                
            case "llm.seed":
                seed = Long.parseLong(value);
                break;
                
            case "llm.api.url":
                apiUrl = new URL(value);
                break;
                
            case "llm.api.token":
                apiToken = value;
                break;
                
            default:
                LOG.warning(() -> "Unknown configuration key " + key);
                break;
            }
        }
    }
    
    public void log() {
        LOG.config("LLM Configuration:");
        LOG.config(() -> "    Model: " + model);
        LOG.config(() -> "    Max code context lines: " + maxCodeContext);
        LOG.config(() -> "    Temperature: " + (temperature != null ? temperature : "<not set>"));
        LOG.config(() -> "    Seed: " + (seed != null ? seed : "<not set>"));
        LOG.config(() -> "    API URL: " + apiUrl);
        LOG.config(() -> "    API token: " + (apiToken != null ? "<redacted>" : "<not set>"));
    }
    
    public String getModel() {
        return model;
    }
    
    public int getMaxCodeContext() {
        return maxCodeContext;
    }
    
    public Double getTemperature() {
        return temperature;
    }

    public Long getSeed() {
        return seed;
    }

    public URL getApiUrl() {
        return apiUrl;
    }
    
    public String getApiToken() {
        return apiToken;
    }
    
}
