package net.ssehub.program_repair.geneseer.llm.openai;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.llm.AbstractLlmApiConnection;
import net.ssehub.program_repair.geneseer.llm.LlmQuery;
import net.ssehub.program_repair.geneseer.llm.openai.ChatGptResponse.Choice;
import net.ssehub.program_repair.geneseer.llm.openai.ChatGptResponse.FinishReason;

public class ChatGptConnection extends AbstractLlmApiConnection {
    
    private static final Logger LOG = Logger.getLogger(ChatGptConnection.class.getName());
    
    public ChatGptConnection(URL apiUrl) {
        super(apiUrl);
    }
    
    @Override
    protected Map<String, Object> queryToJson(LlmQuery query) {
        Map<String, Object> json = new LinkedHashMap<>();
        
        json.put("model", query.getModel());
        json.put("messages", query.getMessages().stream()
                .map(m -> {
                    Map<String, String> messageJson = new LinkedHashMap<>();
                    messageJson.put("role", m.getRole().name().toLowerCase());
                    messageJson.put("content", m.getContent());
                    return m;
                })
                .toList());
        json.put("seed", query.getSeed());
        json.put("temperature", getTemperature());
        
        return json;
    }
    
    @Override
    protected ChatGptResponse parseResponse(String content, LlmQuery query) throws JsonParseException {
        ChatGptResponse response = getGson().fromJson(content, ChatGptResponse.class);
        sanityChecks(response, query);
        LOG.info(() -> "ChatGPT response usage: " + response.usage());
        return response;
    }
    
    private void sanityChecks(ChatGptResponse response, LlmQuery query) throws JsonParseException {
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
        
        if (!query.getModel().equals(response.model())) {
            warnings.add("Response model (" + response.model() + ") does not equal query model ("
                    + query.getModel() + ")");
        }
        
        if (response.systemFingerprint() == null) {
            warnings.add("Response has no system_fingerprint");
        }
        
        if (!"chat.completion".equals(response.object())) {
            warnings.add("Response object is not \"chat.completion\"");
        }
        
        if (response.usage() == null) {
            warnings.add("Response usage is null");
        }
        
        if (!warnings.isEmpty()) {
            LOG.warning(() -> "Got sanity check warnings for response " + response);
            for (String warning : warnings) {
                LOG.warning(warning);
            }
        }
    }
    
}
