package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

public class LlmConfiguration {

    public static final LlmConfiguration INSTANCE = new LlmConfiguration();
    
    private static final Logger LOG = Logger.getLogger(LlmConfiguration.class.getName());
    
    private String model = "dummy";
    
    private int maxCodeContext = 100;
    
    private String url;
    
    private String loginUrl;
    
    private String loginUsername;
    
    private String loginPassword;
    
    private String logoutUrl;
    
    private boolean ignoreCertificates;
    
    private String userAgent;
    
    public void loadFromFile(Path file) throws IOException {
        LOG.info(() -> "Loading configuration file " + file.toAbsolutePath());
        Properties properties = new Properties();
        properties.load(Files.newBufferedReader(file));
        
        for (Object key : properties.keySet()) {
            String value = properties.getProperty((String) key);
            
            switch ((String) key) {
            case "llm.model":
                model = value;
                break;
                
            case "llm.maxCodeContext":
                maxCodeContext = Integer.parseInt(value);
                break;
                
            case "llm.http.url":
                url = value;
                break;
                
            case "llm.http.login.url":
                loginUrl = value;
                break;
            case "llm.http.login.username":
                loginUsername = value;
                break;
            case "llm.http.login.password":
                loginPassword = value;
                break;
                
            case "llm.http.logout.url":
                logoutUrl = value;
                break;
                
            case "llm.http.ignoreCertificates":
                ignoreCertificates = Boolean.parseBoolean(value);
                break;
                
            case "llm.http.userAgent":
                userAgent = value;
                break;
            
            default:
                LOG.warning(() -> "Unknown configuration key " + key);
                break;
            }
        }
    }
    
    public void log() {
        LOG.config("LLM Configuration:");
        LOG.config(() -> "    Model: " + model);
        LOG.config(() -> "    Max code context lines: " + maxCodeContext);
        LOG.config(() -> "    URL: " + url);
        LOG.config(() -> "    Login URL: " + loginUrl);
        LOG.config(() -> "    Login username: " + (loginUsername != null ? "<redacted>" : "<not set>"));
        LOG.config(() -> "    Login password: " + (loginPassword != null ? "<redacted>" : "<not set>"));
        LOG.config(() -> "    Logout URL: " + loginUrl);
        LOG.config(() -> "    Ignore TLS certificates: " + ignoreCertificates);
        LOG.config(() -> "    User-Agent: " + userAgent);
    }
    
    public String getModel() {
        return model;
    }
    
    public int getMaxCodeContext() {
        return maxCodeContext;
    }
    
    public String getUrl() {
        return url;
    }
    
    public String getLoginUrl() {
        return loginUrl;
    }
    
    public String getLoginUsername() {
        return loginUsername;
    }
    
    public String getLoginPassword() {
        return loginPassword;
    }
    
    public String getLogoutUrl() {
        return logoutUrl;
    }
    
    public boolean getIgnoreCertificates() {
        return ignoreCertificates;
    }
    
    public String getUserAgent() {
        return userAgent;
    }
    
}
