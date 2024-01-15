package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

public class ChatGptConnection {
    
    private static final Logger LOG = Logger.getLogger(ChatGptConnection.class.getName());
    
    private static final Gson GSON = new Gson();
    
    private static boolean certificateIgnoringSetup = false;
    
    private CookieManager cookieManager = new CookieManager();
    
    private static void ignoreCertificates() {
        if (certificateIgnoringSetup) {
            return;
        }
        certificateIgnoringSetup = true;
        
        TrustManager trustAll = new X509ExtendedTrustManager() {

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
            }
        };
        
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[] {trustAll}, new SecureRandom());
            SSLContext.setDefault(tls);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            LOG.log(Level.WARNING, "Failed to create SSLContext to accept all certificates", e);
        }
    }
    
    private HttpURLConnection createConnection(URL url) throws IOException {
        if (LlmConfiguration.INSTANCE.getIgnoreCertificates()) {
            ignoreCertificates();
        }
        CookieManager.setDefault(cookieManager);
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        http.setRequestProperty("Accept", "*/*");
        http.setRequestProperty("User-Agent", LlmConfiguration.INSTANCE.getUserAgent());
        return http;
    }
    
    private void writePost(HttpURLConnection http, String contentType, String content) throws IOException {
        http.setRequestMethod("POST");
        if (contentType != null) {
            http.setRequestProperty("Content-Type", contentType);
        }
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
    
    private String trySendRequest(ChatGptRequest request) throws IOException {
        HttpURLConnection http = createConnection(new URL(LlmConfiguration.INSTANCE.getUrl()));
        writePost(http, "application/json", GSON.toJson(request));
        return readContent(http);
    }
    
    public void login() throws IOException {
        HttpURLConnection http = createConnection(new URL(LlmConfiguration.INSTANCE.getLoginUrl()));
        
        String usernameEncoded = URLEncoder.encode(LlmConfiguration.INSTANCE.getLoginUsername(),
                StandardCharsets.UTF_8);
        String passwordEncoded = URLEncoder.encode(LlmConfiguration.INSTANCE.getLoginPassword(),
                StandardCharsets.UTF_8);
        writePost(http, null, "account=" + usernameEncoded + "&password=" + passwordEncoded);
        
        int status = http.getResponseCode();
        if (status != 200) {
            throw new IOException("Login not succesful (HTTP status " + status + ")");
        }
        LOG.fine("Login succesful");
    }
    
    public ChatGptResponse send(ChatGptRequest request) throws IOException {
        LOG.info("Sending query to LLM");
        
        String content;
        try {
            content = trySendRequest(request);
        } catch (IOException e) {
            if (e.getMessage().contains("401")) {
                LOG.info("Got 401, trying to log in");
                login();
                content = trySendRequest(request);           
            } else {
                throw e;
            }
        }
        
        try {
            ChatGptResponse response = GSON.fromJson(content, ChatGptResponse.class);
            LOG.info(() -> "ChatGPT response usage: " + response.usage());
            return response;
        } catch (JsonSyntaxException e) {
            throw new IOException("Failed to parse JSON response " + content, e);
        }
    }

    public void logout() throws IOException {
        HttpURLConnection http = createConnection(new URL(LlmConfiguration.INSTANCE.getLogoutUrl()));
        
        int status = http.getResponseCode();
        if (status != 200) {
            LOG.warning(() -> "Logout not succesful (HTTP status " + status + ")");
        } else {
            LOG.fine("Logout succesful");
        }
    }
    
}
