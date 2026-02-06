package net.ssehub.program_repair.geneseer.logging;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class LoggingConfiguration {
    
    private static final CountingHandler COUNTING_HANDLER = new CountingHandler();

    public LoggingConfiguration() {
        defaultLoggingConfiguration();
    }
    
    public static void defaultLoggingConfiguration() {
        LogManager manager = LogManager.getLogManager();
        manager.reset();
        
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(new SingleLineFormatter());
        console.setLevel(Level.ALL);
        
        Logger rootLogger = manager.getLogger("");
        rootLogger.addHandler(COUNTING_HANDLER);
        rootLogger.addHandler(console);
        rootLogger.setLevel(Level.CONFIG);
        
        String logLevelProperty = System.getProperty("geneseer.logLevel"); 
        if (logLevelProperty != null) {
            try {
                Level level = Level.parse(logLevelProperty);
                Logger.getLogger("net.ssehub.program_repair.geneseer").setLevel(level);
            } catch (IllegalArgumentException e) {
                Logger.getLogger(LoggingConfiguration.class.getName())
                        .warning(() -> "Invalid log level specified as system property: " + logLevelProperty);
            }
        }
    }
    
    public static int getMessageCount(Level level) {
        return COUNTING_HANDLER.getMessageCount(level);
    }
    
}
