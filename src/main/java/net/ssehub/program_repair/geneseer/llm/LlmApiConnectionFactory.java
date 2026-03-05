package net.ssehub.program_repair.geneseer.llm;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.llm.ollama.OllamaConnection;
import net.ssehub.program_repair.geneseer.llm.openai.ChatGptConnection;

public class LlmApiConnectionFactory {
    
    private static final Logger LOG = Logger.getLogger(LlmApiConnectionFactory.class.getName());
    
    public ILlmApiConnection create() throws IllegalArgumentException {
        String url = Configuration.INSTANCE.llm().apiUrl();
        
        ILlmApiConnection result;
        if (url.equals("dummy")) {
            LOG.warning("llm.url is set to \"dummy\"; not using a real LLM");
            result = new DummyLlmConnection();
            
        } else if (url.startsWith("openai:")) {
            url = url.substring("openai:".length());
            ChatGptConnection con = new ChatGptConnection(parse(url));
            applySettings(con);
            result = con;
            
        } else if (url.startsWith("ollama:")) {
            url = url.substring("ollama:".length());
            OllamaConnection con = new OllamaConnection(parse(url));
            applySettings(con);
            con.setThink(Configuration.INSTANCE.llm().think());
            con.setContextSize(Configuration.INSTANCE.llm().contextSize());
            result = con;
            
        } else {
            throw new IllegalArgumentException("Invalid LLM url: " + url);
        }
        
        return result;
    }
    
    private void applySettings(AbstractLlmApiConnection con) {
        con.setToken(Configuration.INSTANCE.llm().apiToken());
        con.setUserHeader(Configuration.INSTANCE.llm().apiUserHeader());
        con.setThinkingDelimiter(Configuration.INSTANCE.llm().thinkingDelimiter());
    }
    
    private URL parse(String url) throws IllegalArgumentException {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
    
}
