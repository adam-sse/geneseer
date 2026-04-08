package net.ssehub.program_repair.geneseer.llm.ollama;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.llm.AbstractLlm;
import net.ssehub.program_repair.geneseer.llm.Query;
import net.ssehub.program_repair.geneseer.llm.Role;

// https://docs.ollama.com/api/chat
public class OllamaLlm extends AbstractLlm {
    
    private static final Logger LOG = Logger.getLogger(OllamaLlm.class.getName());
    
    private Long contextSize;
    
    public OllamaLlm(String model, URL apiUrl) {
        super(model, apiUrl);
    }
    
    public void setContextSize(Long contextSize) {
        this.contextSize = contextSize;
    }
    
    @Override
    protected Map<String, Object> queryToJson(Query query) {
        Map<String, Object> json = new LinkedHashMap<>();
        
        json.put("model", getModel());
        json.put("messages", query.getMessages().stream()
                .map(m -> {
                    Map<String, String> messageJson = new LinkedHashMap<>();
                    messageJson.put("role", m.getRole().name().toLowerCase());
                    messageJson.put("content", m.getContent());
                    return m;
                })
                .toList());
        json.put("stream", false);
        if (query.getJsonSchema() != null) {
            json.put("format", query.getJsonSchema());
        }
        if (getThink() != null) {
            if (getThink().equals("true")) {
                json.put("think", true);
            } else if (getThink().equals("false")) {
                json.put("think", false);
            } else {
                json.put("think", getThink());
            }
        }
        
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("seed", query.getSeed());
        options.put("temperature", getTemperature());
        options.put("num_ctx", contextSize);
        if (options.size() > 0) {
            json.put("options", options);
        }
        
        return json;
    }
    
    @Override
    protected OllamaResponse parseResponse(String content, Query query) throws JsonParseException {
        OllamaResponse response = getGson().fromJson(content, OllamaResponse.class);
        sanityChecks(response, query);
        LOG.info(() -> {
            StringBuilder sb = new StringBuilder("Token usage: ");
            sb.append("query tokens: ").append(response.promptEvalCount());
            sb.append(", answer tokens: ").append(response.evalCount());
            return sb.toString();
        });
        return response;
    }
    
    private void sanityChecks(OllamaResponse response, Query query) throws JsonParseException {
        List<String> warnings = new LinkedList<>();
        
        if (response.message() == null || response.message().getContent() == null) {
            throw new JsonParseException("Got no response message");
        }
        
        if (response.message().getRole() != Role.ASSISTANT) {
            warnings.add("Role of response message is not ASSISTANT, but " + response.message().getRole());
        }
        
        if (!response.done()) {
            warnings.add("Response is not \"done\"");
        }
        
        if (!getModel().equals(response.model())) {
            warnings.add("Response model (" + response.model() + ") does not equal query model (" + getModel() + ")");
        }
        
        if (isThinkingExplicitlyEnabled() && response.message().getThinking() == null) {
            warnings.add("Thinking is enabled in query but got no thinking output in response");
        }
        if (isThinkingExplicitlyDisabled() && response.message().getThinking() != null) {
            warnings.add("Thinking is disabled in query but got thinking output in response");
        }
        
        if (!warnings.isEmpty()) {
            LOG.warning(() -> "Got sanity check warnings for response " + response);
            for (String warning : warnings) {
                LOG.warning(warning);
            }
        }
    }
    
}
