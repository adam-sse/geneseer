package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import net.ssehub.program_repair.geneseer.llm.LlmMessage.Role;

public abstract class AbstractLlm implements ILlm {
    
    private static final Logger LOG = Logger.getLogger(AbstractLlm.class.getName());
    
    private URL apiUrl;
    
    private String token;
    
    private String userHeader;
    
    private String thinkingDelimiter;
    
    private Double temperature;
    
    private Gson gson;
    
    public AbstractLlm(URL apiUrl) {
        this.apiUrl = apiUrl;
        
        GsonBuilder gsonBuilder = new GsonBuilder();
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
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public void setUserHeader(String userHeader) {
        this.userHeader = userHeader;
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
    public ILlmResponse send(LlmQuery query) throws IOException {
        LOG.info("Sending query to LLM: " + query);
        
        HttpURLConnection http = createConnection();
        writePost(http, gson.toJson(queryToJson(query)));
        String content = readContent(http);
        logRateLimitHeaders(http);
        
        try {
            ILlmResponse response = parseResponse(content, query);
            if (thinkingDelimiter != null) {
                removeThinkingTrace(response);
            }
            return response;
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse JSON response " + content, e);
        }
    }

    private HttpURLConnection createConnection() throws IOException {
        HttpURLConnection http = (HttpURLConnection) apiUrl.openConnection();
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Content-Type", "application/json");
        if (userHeader != null) {
            http.setRequestProperty("x-user", userHeader);
        }
        if (token != null) {
            http.setRequestProperty("Authorization", "Bearer " + token);
        }
        return http;
    }
    
    protected abstract Map<String, Object> queryToJson(LlmQuery query);
    
    private void writePost(HttpURLConnection http, String content) throws IOException {
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        try (OutputStream out = http.getOutputStream()) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
    
    private String readContent(HttpURLConnection http) throws IOException {
        try (InputStream in = http.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logErrorMessage(http);
            throw e;
        } finally {
            http.disconnect();
        }
    }

    private void logErrorMessage(HttpURLConnection http) {
        try {
            int statusCode = http.getResponseCode();
            if (statusCode == -1) {
                LOG.warning("Received invalid HTTP response");
            } else {
                String responseMessage = http.getResponseMessage();
                LOG.warning(() -> "Received " + statusCode + " " + responseMessage);
                
                String content = new String(http.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                LOG.warning(() -> "Error message: " + content);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to query error information", e);
        }
    }
    
    private void logRateLimitHeaders(HttpURLConnection http) {
        LOG.info("Ratelimit HTTP headers:");
        for (Map.Entry<String, List<String>> header : http.getHeaderFields().entrySet()) {
            if (header.getKey() != null && header.getKey().toLowerCase().startsWith("x-ratelimit-")) {
                String value;
                if (header.getValue().size() == 1) {
                    value = header.getValue().get(0);
                } else {
                    value = header.getValue().toString();
                }
                LOG.info(() -> "    " + header.getKey() + ": " + value);
            }
        }
    }
    
    protected abstract ILlmResponse parseResponse(String content, LlmQuery query) throws JsonParseException;
    
    protected Gson getGson() {
        return gson;
    }
    
    private void removeThinkingTrace(ILlmResponse response) {
        for (LlmMessage message : response.getMessages()) {
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
