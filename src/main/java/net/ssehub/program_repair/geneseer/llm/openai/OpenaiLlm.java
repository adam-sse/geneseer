package net.ssehub.program_repair.geneseer.llm.openai;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.llm.AbstractLlm;
import net.ssehub.program_repair.geneseer.llm.LlmQuery;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiResponse.Choice;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiResponse.FinishReason;

public class OpenaiLlm extends AbstractLlm {
    
    private static final Logger LOG = Logger.getLogger(OpenaiLlm.class.getName());
    
    public OpenaiLlm(String model, URL apiUrl) {
        super(model, apiUrl);
    }
    
    @Override
    protected Map<String, Object> queryToJson(LlmQuery query) {
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
        json.put("reasoning_effort", getThink());
        json.put("temperature", getTemperature());
        
        if (query.getSeed() != null) {
            LOG.warning("Specifying a seed is not supported by the openai API");
        }
        
        return json;
    }
    
    @Override
    protected OpenaiResponse parseResponse(String content, LlmQuery query) throws JsonParseException {
        OpenaiResponse response = getGson().fromJson(content, OpenaiResponse.class);
        sanityChecks(response, query);
        LOG.info(() -> "Token usage: " + response.usage());
        return response;
    }
    
    private void sanityChecks(OpenaiResponse response, LlmQuery query) throws JsonParseException {
        List<String> warnings = new LinkedList<>();
        
        if (response.id() == null) {
            warnings.add("Response id is null");
        }
        
        if (response.choices() == null || response.choices().isEmpty()) {
            throw new JsonParseException("Got no choices in response");
        }
        if (response.choices().size() != 1) {
            warnings.add("Response has " + response.choices().size() + " choices, expected 1; only using first");
        }
        for (int i = 0; i < response.choices().size(); i++) {
            Choice choice = response.choices().get(i);
            if (choice.finishReason() != FinishReason.STOP) {
                warnings.add("Finish reason of choice " + i + " is not STOP, but: " + choice.finishReason());
            }
            if (choice.index() != i) {
                warnings.add("Index of choice " + i + " is " + choice.index());
            }
            if (choice.message() == null) {
                throw new JsonParseException("message of choice " + i + " is null");
            }
        }
        
        if (!getModel().equals(response.model())) {
            warnings.add("Response model (" + response.model() + ") does not equal query model (" + getModel() + ")");
        }
        
        if (!"chat.completion".equals(response.object())) {
            warnings.add("Response object is not \"chat.completion\"");
        }
        
        if (response.usage() == null) {
            warnings.add("Response usage is null");
        } else {
            if (response.usage().completionTokensDetails() == null
                    || response.usage().completionTokensDetails().reasoningTokens() == null) {
                warnings.add("Response completion thinking tokens not reported");
            } else {
                int thinkingTokens = response.usage().completionTokensDetails().reasoningTokens();
                if (isThinkingExplicitlyEnabled() && thinkingTokens == 0) {
                    warnings.add("Thinking is enabled in query but got no thinking output in response");
                }
                if (isThinkingExplicitlyDisabled() && thinkingTokens > 0) {
                    warnings.add("Thinking is disabled in query but got thinking output in response");
                }
            }
        }
        
        if (!warnings.isEmpty()) {
            LOG.warning(() -> "Got sanity check warnings for response " + response);
            for (String warning : warnings) {
                LOG.warning(warning);
            }
        }
    }
    
}
