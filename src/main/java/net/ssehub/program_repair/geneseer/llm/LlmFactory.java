package net.ssehub.program_repair.geneseer.llm;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Configuration.LlmConfiguration;
import net.ssehub.program_repair.geneseer.llm.ollama.OllamaLlm;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiLlm;

public class LlmFactory {
    
    private static final Logger LOG = Logger.getLogger(LlmFactory.class.getName());
    
    private String model;
    
    private String apiUrl;
    
    private String apiToken;
    
    private String apiUserHeader;
    
    private String think;
    
    private String thinkingDelimiter;
    
    private Long contextSize;
    
    private Double temperature;
    
    public static LlmFactory fromConfiguration(LlmConfiguration configuration) {
        return new LlmFactory()
                .withModel(configuration.model())
                .withApiUrl(configuration.apiUrl())
                .withApiToken(configuration.apiToken())
                .withApiUserHeader(configuration.apiUserHeader())
                .withThink(configuration.think())
                .withThinkingDelimiter(configuration.thinkingDelimiter())
                .withContextSize(configuration.contextSize())
                .withTemperature(configuration.temperature());
    }
    
    public LlmFactory withModel(String model) {
        this.model = model;
        return this;
    }
    
    public LlmFactory withApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
        return this;
    }
    
    public LlmFactory withApiToken(String apiToken) {
        this.apiToken = apiToken;
        return this;
    }
    
    public LlmFactory withApiUserHeader(String apiUserHeader) {
        this.apiUserHeader = apiUserHeader;
        return this;
    }
    
    public LlmFactory withThink(String think) {
        this.think = think;
        return this;
    }
    
    public LlmFactory withThinkingDelimiter(String thinkingDelimiter) {
        this.thinkingDelimiter = thinkingDelimiter;
        return this;
    }
    
    public LlmFactory withContextSize(Long contextSize) {
        this.contextSize = contextSize;
        return this;
    }
    
    public LlmFactory withTemperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }
    
    public ILlm create() throws IllegalArgumentException {
        if (model == null) {
            throw new IllegalArgumentException("No model specified");
        }
        
        ILlm result;
        if (model.equals("dummy")) {
            LOG.warning("llm.model is set to \"dummy\"; not using a real LLM");
            result = new DummyLlm();
        } else {
            if (apiUrl == null) {
                throw new IllegalArgumentException("No API URL specified");
            }
            
            if (apiUrl.startsWith("openai:")) {
                apiUrl = apiUrl.substring("openai:".length());
                OpenaiLlm con = new OpenaiLlm(model, parse(apiUrl));
                applyCommonSettings(con);
                result = con;
                if (contextSize != null) {
                    LOG.warning(() -> "Context size setting is not supported for openai API");
                }
                
            } else if (apiUrl.startsWith("ollama:")) {
                apiUrl = apiUrl.substring("ollama:".length());
                OllamaLlm con = new OllamaLlm(model, parse(apiUrl));
                applyCommonSettings(con);
                con.setContextSize(contextSize);
                result = con;
                
            } else {
                throw new IllegalArgumentException("Invalid LLM url: " + apiUrl);
            }
        }
        
        return result;
    }
    
    private void applyCommonSettings(AbstractLlm con) {
        con.setApiToken(apiToken);
        con.setApiUserHeader(apiUserHeader);
        con.setThink(think);
        con.setThinkingDelimiter(thinkingDelimiter);
        con.setTemperature(temperature);
    }
    
    private URL parse(String url) throws IllegalArgumentException {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
    
}
