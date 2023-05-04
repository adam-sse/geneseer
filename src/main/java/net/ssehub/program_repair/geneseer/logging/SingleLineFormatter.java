package net.ssehub.program_repair.geneseer.logging;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

class SingleLineFormatter extends Formatter {

    private static final DateTimeFormatter TIME_FORMATTER
    = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    @Override
    public String format(LogRecord record) {
        StringBuilder logLine = new StringBuilder();
        
        
        logLine.append('[').append(TIME_FORMATTER.format(record.getInstant())).append("] ");
        logLine.append('[').append(record.getLevel().getName()).append("] ");
        
        if (record.getSourceClassName() != null) {
            logLine.append('[').append(record.getSourceClassName()).append("] ");
        }
        
        int prefixLength = logLine.length();
        
        logLine.append(formatMessage(record));
        logLine.append('\n');
        
        if (record.getThrown() != null) {
            logException(logLine, prefixLength, record.getThrown());
        }
        
        return logLine.toString();
    }
    
    /**
    * Converts a given exception to a string and adds it to the log output.
    * 
    * @param logBuffer The log output to add the lines to.
    * @param prefixLength The number of whitespace characters to add in front of each line.
    * @param exception The exception to convert into a string.
    */
    private static void logException(StringBuilder logBuffer, int prefixLength, Throwable exception) {
        String prefix = " ".repeat(prefixLength);
        
        logBuffer.append(prefix).append(exception.getClass().getName());
        if (exception.getMessage() != null) {
            logBuffer.append(": ").append(exception.getMessage());
        }
        logBuffer.append('\n');
        
        StackTraceElement[] stack = exception.getStackTrace();
        for (StackTraceElement stackElement : stack) {
            logBuffer.append(prefix).append("  at ").append(stackElement.toString()).append('\n');
        }
        
        Throwable cause = exception.getCause();
        if (cause != null) {
            logBuffer.append(prefix).append("Caused by:").append('\n');
            logException(logBuffer, prefixLength, cause);
        }
    }
    
}
