package net.ssehub.program_repair.geneseer.logging;

import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class LoggingConfiguration {

    public LoggingConfiguration() {
        defaultLoggingConfiguration();
        
        try {
            LogManager.getLogManager().updateConfiguration(null);
        } catch (IOException e) {
            Logger log = Logger.getLogger(LoggingConfiguration.class.getName());
            log.log(Level.SEVERE, "Failed to read logger configuration file "
                    + System.getProperty("java.util.logging.config.file"), e);
        }
    }
    
    public static void defaultLoggingConfiguration() {
        LogManager manager = LogManager.getLogManager();
        manager.reset();
        
//        StreamHandler console = new StreamHandler(System.out, new SingleLineFormatter());
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(new SingleLineFormatter());
        console.setLevel(Level.ALL);
        
        Logger rootLogger = manager.getLogger("");
        rootLogger.addHandler(console);
        rootLogger.setLevel(Level.INFO);
    }
    
}
