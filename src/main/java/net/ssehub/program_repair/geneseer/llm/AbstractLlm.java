package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public abstract class AbstractLlm implements ILlm {
    
    private static final Logger LOG = Logger.getLogger(AbstractLlm.class.getName());
    
    private static final int MAX_RETRIES = 2;
    
    private String model;
    
    private URI apiUrl;
    
    private String apiToken;
    
    private long timeoutMs;
    
    private String think;
    
    private String thinkingDelimiter;
    
    private Double temperature;
    
    private HttpClient http;
    
    private Gson gson;
    
    public AbstractLlm(String model, URL apiUrl) {
        this.model = model;
        try {
            this.apiUrl = apiUrl.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES);
        gsonBuilder.registerTypeAdapter(Role.class, new TypeAdapter<Role>() {
            @Override
            public void write(JsonWriter out, Role value) throws IOException {
                out.value(value.name().toLowerCase());
            }
            @Override
            public Role read(JsonReader in) throws IOException {
                return Role.valueOf(in.nextString().toUpperCase());
            }
        });
        this.gson = gsonBuilder.create();
        
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }
    
    protected String getModel() {
        return model;
    }
    
    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }
    
    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
    
    public void setThink(String think) {
        this.think = think;
    }
    
    protected String getThink() {
        return think;
    }
    
    protected boolean isThinkingExplicitlyEnabled() {
        return think != null && !isThinkingExplicitlyDisabled();
    }
    
    protected boolean isThinkingExplicitlyDisabled() {
        return think != null && (think.equals("false") || think.equals("none"));
    }
    
    public void setThinkingDelimiter(String thinkingDelimiter) {
        this.thinkingDelimiter = thinkingDelimiter;
    }
    
    public void setTemperature(Double temperature) throws IllegalArgumentException {
        if (temperature != null && (temperature < 0.0 || temperature > 2.0)) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
        this.temperature = temperature;
    }
    
    protected Double getTemperature() {
        return temperature;
    }
    
    @Override
    public IResponse send(Query query) throws IOException {
        String content = executePost(gson.toJson(queryToJson(query)));
        try {
            IResponse response = parseResponse(content, query);
            if (thinkingDelimiter != null) {
                removeThinkingTrace(response);
            }
            return response;
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse JSON response " + content, e);
        }
    }
    
    private String executePost(String content) throws IOException {
        HttpRequest request = createRequest(content);
        
        String result = null;
        int retries = 0;
        while (result == null) {
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Failed to run HTTP call", e);
            }
            
            int status = response.statusCode();
            String body = response.body();
            
            if (status >= 200 && status < 300) {
                result = body;
            } else if (status >= 500 && status < 600) {
                if (retries < MAX_RETRIES) {
                    retries++;
                    LOG.warning("HTTP request failed with " + status + ": " + body + ". Retrying ("
                            + retries + "/" + MAX_RETRIES + ")");
                } else {
                    throw new IOException("HTTP request failed after " + MAX_RETRIES + " retries with "
                            + status + ": " + body); 
                }
            } else {
                throw new IOException("HTTP request failed with " + status + ": " + body);
            }
        }
        
        return result;
    }
    
    private HttpRequest createRequest(String content) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(apiUrl)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (apiToken != null) {
            builder.header("Authorization", "Bearer " + apiToken);
        }
        if (timeoutMs != 0) {
            builder.timeout(Duration.ofMillis(timeoutMs));
        }
        return builder
                .POST(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
                .build();
    }
    
    protected abstract Map<String, Object> queryToJson(Query query);
    
    protected abstract IResponse parseResponse(String content, Query query) throws JsonParseException;
    
    protected Gson getGson() {
        return gson;
    }
    
    private void removeThinkingTrace(IResponse response) {
        for (Message message : response.getMessages()) {
            if (message.getRole() == Role.ASSISTANT && message.getThinking() == null) {
                int index = message.getContent().lastIndexOf(thinkingDelimiter);
                LOG.fine(() -> "Stripping " + index + " characters of thinking trace");
                if (index != -1) {
                    String thinking = message.getContent().substring(0, index);
                    message.setThinking(thinking);
                    message.setContent(message.getContent().substring(index + thinkingDelimiter.length()));
                }
            }
        }
    }
    
}
