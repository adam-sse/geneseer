package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.llm.ChatGptResponse.Choice;
import net.ssehub.program_repair.geneseer.llm.ChatGptResponse.FinishReason;

public class ChatGptConnection implements IChatGptConnection {
    
    private static final Logger LOG = Logger.getLogger(ChatGptConnection.class.getName());
    
    private URL apiUrl;
    
    private String token;
    
    private Gson gson;
    
    public ChatGptConnection(URL apiUrl, String token) {
        this.apiUrl = apiUrl;
        this.token = token;
        this.gson = new Gson();
    }
    
    @Override
    public ChatGptResponse send(ChatGptRequest request) throws IOException {
        LOG.info("Sending query to LLM: " + request);
        
        HttpURLConnection http = createConnection();
        writePost(http, gson.toJson(request));
        String content = readContent(http);
        
        try {
            return parseResponse(content, request);
        } catch (JsonParseException e) {
            throw new IOException("Failed to parse JSON response " + content, e);
        }
    }

    private HttpURLConnection createConnection() throws IOException {
        HttpURLConnection http = (HttpURLConnection) apiUrl.openConnection();
        http.setRequestProperty("Accept", "application/json");
        http.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            http.setRequestProperty("Authorization", "Bearer " + token);
        }
        return http;
    }
    
    private void writePost(HttpURLConnection http, String content) throws IOException {
        http.setRequestMethod("POST");
        http.setDoOutput(true);
        http.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
    }
    
    private String readContent(HttpURLConnection http) throws IOException {
        try (InputStream in = http.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            http.disconnect();
        }
    }
    
    private ChatGptResponse parseResponse(String content, ChatGptRequest request) throws JsonParseException {
        ChatGptResponse response = gson.fromJson(content, ChatGptResponse.class);
        sanityChecks(response, request);
        LOG.info(() -> "ChatGPT response usage: " + response.usage());
        return response;
    }
    
    private void sanityChecks(ChatGptResponse response, ChatGptRequest request) throws JsonParseException {
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
        
        if (!request.getModel().equals(response.model())) {
            warnings.add("Response model (" + response.model() + ") does not equal request model ("
                    + request.getModel() + ")");
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
