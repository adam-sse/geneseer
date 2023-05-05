package net.ssehub.program_repair.geneseer;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

public class Configuration {

    public static final Configuration INSTANCE = new Configuration();
    
    private Charset encoding;
    
    private Configuration() {
        encoding = Charset.defaultCharset();
    }
    
    public String getJvmBinaryPath() {
        return "java";
    }
    
    public String getJavaCompilerBinaryPath() {
        return "javac";
    }
    
    public Charset getEncoding() {
        return encoding;
    }
    
    public int getTestExecutionTimeoutMs() {
        return (int) TimeUnit.MINUTES.toMillis(5);
    }
    
}
