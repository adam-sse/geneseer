package net.ssehub.program_repair.geneseer.llm.openai;

import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.llm.AbstractLlm;
import net.ssehub.program_repair.geneseer.llm.IResponse;
import net.ssehub.program_repair.geneseer.llm.Message;
import net.ssehub.program_repair.geneseer.llm.Query;
import net.ssehub.program_repair.geneseer.llm.Role;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiResponse.Choice;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiResponse.FinishReason;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiResponse.Usage;

// https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
public class OpenaiLlm extends AbstractLlm {
    
    private static final Logger LOG = Logger.getLogger(OpenaiLlm.class.getName());
    
    public OpenaiLlm(String model, URL apiUrl) {
        super(model, apiUrl);
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
                    return messageJson;
                })
                .toList());
        if (query.getJsonSchema() != null) {
            json.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "generic-schema",
                            "strict", true,
                            "schema", query.getJsonSchema())));
        }
        json.put("reasoning_effort", getThink());
        json.put("temperature", getTemperature());
        json.put("stream", true);
        json.put("stream_options", Map.of("include_usage", true));
        
        if (query.getSeed() != null) {
            LOG.warning("Specifying a seed is not supported by the openai API");
        }
        
        return json;
    }
    
    @Override
    protected IResponse parseResponse(String content, Query query) throws JsonParseException {
        List<String> dataLines = Arrays.stream(content.split("\\R"))
                .filter(n -> !n.isBlank())
                .collect(Collectors.toList());
        
        Optional<String> invalidLine = dataLines.stream()
                .filter(n -> !n.startsWith("data: "))
                .findFirst();
        if (invalidLine.isPresent()) {
            throw new JsonParseException("invalid data line: " + invalidLine.get());
        }
        dataLines = dataLines.stream()
                .map(l -> l.substring("data: ".length()))
                .collect(Collectors.toList());
        
        if (dataLines.isEmpty()) {
            throw new JsonParseException("got no data lines");
        }
        if (!dataLines.get(dataLines.size() - 1).equals("[DONE]")) {
            throw new JsonParseException("last line is not [DONE] but " + dataLines.get(dataLines.size() - 1));
        }
        dataLines.remove(dataLines.size() - 1);
        
        List<OpenaiResponse> chunkedResponses = dataLines.stream()
                .map(l -> getGson().fromJson(l, OpenaiResponse.class))
                .toList();
        sanityChecks(chunkedResponses, query);
        
        List<Message> messages = chunkedResponses.stream()
                .filter(r -> !r.choices().isEmpty())
                .map(r -> r.choices().get(0))
                .map(Choice::delta)
                .toList();
        
        Role role = messages.stream()
                .map(Message::getRole)
                .filter(Objects::nonNull)
                .findFirst().orElse(Role.ASSISTANT);
        String combinedContent = messages.stream()
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        String combinedThinking = messages.stream()
                .map(Message::getThinking)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        Message combined = new Message(role, combinedContent);
        if (!combinedThinking.isEmpty()) {
            combined.setThinking(combinedThinking);
        }
        
        int queryTokens = 0;
        int answerTokens = 0;
        Usage usage = chunkedResponses.get(chunkedResponses.size() - 1).usage();
        if (usage != null) {
            LOG.info(() -> "Token usage: " + usage);
            queryTokens = usage.promptTokens();
            answerTokens = usage.completionTokens();
        }
        
        return new Response(List.of(combined), queryTokens, answerTokens);
    }
    
    private void sanityChecks(List<OpenaiResponse> chunkedResponses, Query query) throws JsonParseException {
        List<String> warnings = new LinkedList<>();
        
        Set<String> uniqueIds = chunkedResponses.stream()
                .map(r -> r.id())
                .collect(Collectors.toSet());
        if (uniqueIds.size() != 1) {
            warnings.add("Got " + uniqueIds.size() + " different IDs in chunks");
        }
        
        OpenaiResponse lastChunk = chunkedResponses.get(chunkedResponses.size() - 1);
        int hasTokenUsage = 1;
        if (lastChunk.choices() != null && !lastChunk.choices().isEmpty()) {
            warnings.add("Last chunk has choices, expected only token usage");
            hasTokenUsage = 0;
        }
        
        for (int chunkIndex = 0; chunkIndex < chunkedResponses.size() - hasTokenUsage; chunkIndex++) {
            sanityCheckChunk(chunkedResponses, warnings, hasTokenUsage, chunkIndex);
        }
        
        if (lastChunk.usage() == null) {
            warnings.add("Response usage is null");
        } else {
            if (lastChunk.usage().completionTokensDetails() == null
                    || lastChunk.usage().completionTokensDetails().reasoningTokens() == null) {
                warnings.add("Response completion thinking tokens not reported");
            } else {
                int thinkingTokens = lastChunk.usage().completionTokensDetails().reasoningTokens();
                if (isThinkingExplicitlyEnabled() && thinkingTokens == 0) {
                    warnings.add("Thinking is enabled in query but got no thinking output in response");
                }
                if (isThinkingExplicitlyDisabled() && thinkingTokens > 0) {
                    warnings.add("Thinking is disabled in query but got thinking output in response");
                }
            }
        }
        
        if (!warnings.isEmpty()) {
            LOG.warning(() -> "Got sanity check warnings for response");
            for (String warning : warnings) {
                LOG.warning(warning);
            }
        }
    }

    private void sanityCheckChunk(List<OpenaiResponse> chunkedResponses, List<String> warnings, int hasTokenUsage,
            int chunkIndex) throws JsonParseException {
        OpenaiResponse chunk = chunkedResponses.get(chunkIndex);
        
        boolean isTokenUsageChunk = chunkIndex >= chunkedResponses.size() - hasTokenUsage;
        boolean isEndChunk = chunkIndex >= chunkedResponses.size() - hasTokenUsage - 1;
        
        if (chunk.id() == null) {
            warnings.add("ID in chunk " + chunkIndex + " is null");
        }
        if (!getModel().equals(chunk.model())) {
            warnings.add("Model in chunk " + chunkIndex + "(" + chunk.model() + ") does not equal query model ("
                    + getModel() + ")");
        }
        if (!"chat.completion.chunk".equals(chunk.object())) {
            warnings.add("Object in chunk " + chunkIndex + " is not \"chat.completion.chunk\", but " + chunk.object());
        }
        
        if (chunk.choices() == null) {
            throw new JsonParseException("Choices in chunk " + chunkIndex + " is null");
        }
        
        if (isTokenUsageChunk) {
            if (!chunk.choices().isEmpty()) {
                warnings.add("Choice in token usage chunk is not empty");
            }
        } else {
            if (isEndChunk) {
                if (chunk.choices().size() > 1) {
                    warnings.add("Number of choices in last content chunk  is not 1 but" + chunk.choices().size());
                }
            } else {
                if (chunk.choices().size() != 1) {
                    warnings.add("Number of choices in chunk" + chunkIndex + " is not 1 but" + chunk.choices().size());
                }
            }
        }
        
        for (int i = 0; i < chunk.choices().size(); i++) {
            Choice choice = chunk.choices().get(i);
            FinishReason expectedFinishReason = isEndChunk ? FinishReason.STOP : null;
            if (choice.finishReason() != expectedFinishReason) {
                warnings.add("Finish reason of chunk " + chunkIndex + " is not " + expectedFinishReason
                        + ", but: " + choice.finishReason());
            }
            if (choice.index() != i) {
                warnings.add("Choice index of chunk " + chunkIndex + " is " + choice.index());
            }
            if (choice.delta() == null) {
                throw new JsonParseException("Message of chunk " + chunkIndex + " is null");
            }
            if (!isEndChunk && choice.delta().getContent() == null) {
                warnings.add("Message content of chunk " + chunkIndex + " is null");
            }
            
            Role expectedRole = chunkIndex == 0 ? Role.ASSISTANT : null;
            if (choice.delta().getRole() != expectedRole) {
                warnings.add("Role of message in chunk " + chunkIndex + " is not " + expectedRole
                        + ", but " + choice.delta().getRole());
            }
        }
    }
    
}
