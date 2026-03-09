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

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public abstract class AbstractLlm implements ILlm {
    
    private static final Logger LOG = Logger.getLogger(AbstractLlm.class.getName());
    
    private String model;
    
    private URL apiUrl;
    
    private String apiToken;
    
    private String think;
    
    private String thinkingDelimiter;
    
    private Double temperature;
    
    private Gson gson;
    
    public AbstractLlm(String model, URL apiUrl) {
        this.model = model;
        this.apiUrl = apiUrl;
        
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
    }
    
    protected String getModel() {
        return model;
    }
    
    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
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
        LOG.info("Sending query to LLM: " + query);
        
        HttpURLConnection http = createConnection();
        writePost(http, gson.toJson(queryToJson(query)));
        String content = readContent(http);
        logRateLimitHeaders(http);
        
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

    private HttpURLConnection createConnection() throws IOException {
        HttpURLConnection http = (HttpURLConnection) apiUrl.openConnection();
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Content-Type", "application/json");
        if (apiToken != null) {
            http.setRequestProperty("Authorization", "Bearer " + apiToken);
        }
        return http;
    }
    
    protected abstract Map<String, Object> queryToJson(Query query);
    
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
        boolean first = true;
        for (Map.Entry<String, List<String>> header : http.getHeaderFields().entrySet()) {
            if (header.getKey() != null && header.getKey().toLowerCase().startsWith("x-ratelimit-")) {
                if (first) {
                    LOG.info("Ratelimit HTTP headers:");
                    first = false;
                }
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
