package net.ssehub.program_repair.geneseer.llm;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.llm.ollama.OllamaLlm;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiLlm;

public class LlmFactory {
    
    private static final Logger LOG = Logger.getLogger(LlmFactory.class.getName());
    
    public ILlm create() throws IllegalArgumentException {
        String model = Configuration.INSTANCE.llm().model();
        String url = Configuration.INSTANCE.llm().apiUrl();
        
        ILlm result;
        if (model.equals("dummy")) {
            LOG.warning("llm.model is set to \"dummy\"; not using a real LLM");
            result = new DummyLlm();
            
        } else if (url.startsWith("openai:")) {
            url = url.substring("openai:".length());
            OpenaiLlm con = new OpenaiLlm(model, parse(url));
            applyStandardSettings(con);
            result = con;
            
        } else if (url.startsWith("ollama:")) {
            url = url.substring("ollama:".length());
            OllamaLlm con = new OllamaLlm(model, parse(url));
            applyStandardSettings(con);
            con.setThink(Configuration.INSTANCE.llm().think());
            con.setContextSize(Configuration.INSTANCE.llm().contextSize());
            result = con;
            
        } else {
            throw new IllegalArgumentException("Invalid LLM url: " + url);
        }
        
        return result;
    }
    
    private void applyStandardSettings(AbstractLlm con) {
        con.setToken(Configuration.INSTANCE.llm().apiToken());
        con.setUserHeader(Configuration.INSTANCE.llm().apiUserHeader());
        con.setThinkingDelimiter(Configuration.INSTANCE.llm().thinkingDelimiter());
        con.setTemperature(Configuration.INSTANCE.llm().temperature());
    }
    
    private URL parse(String url) throws IllegalArgumentException {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
    
}
