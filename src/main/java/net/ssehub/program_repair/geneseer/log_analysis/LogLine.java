package net.ssehub.program_repair.geneseer.log_analysis;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record LogLine(LocalDateTime timestamp, Level level, String className, String content) {
    
    private static final Pattern LINE_PATTERN;
    
    static {
        String timestamp = "(?<timestamp>[0-9]{4}-[0-9]{2}-[0-9]{2}( |T)[0-9]{2}:[0-9]{2}:[0-9]{2})";
        String level = "(?<level>SEVERE|WARNING|INFO|CONFIG|FINE|FINER|FINEST)";
        String className = "(?<className>[a-zA-Z0-9._]+)";
        String content = "(?<content>.*)";
        
        LINE_PATTERN = Pattern.compile("^\\[" + timestamp + "\\] \\[" + level + "\\] \\[" + className + "\\] "
                + content + "$");
    }
    
    public static List<LogLine> parse(List<String> lines) throws IllegalArgumentException {
        List<LogLine> parsedLines = new ArrayList<>(lines.size());
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            
            Matcher matcher = LINE_PATTERN.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(line + " does not match " + LINE_PATTERN.pattern());
            }
            String timestampString = matcher.group("timestamp");
            if (timestampString.charAt(10) == ' ') {
                timestampString = timestampString.substring(0, 10) + 'T' + timestampString.substring(11);
            }
            
            Level level = Level.parse(matcher.group("level"));
            
            StringBuilder content = new StringBuilder(matcher.group("content"));
            
            while (i + 1 < lines.size() && !LINE_PATTERN.matcher(lines.get(i + 1)).matches()) {
                content.append('\n').append(lines.get(i + 1));
                i++;
            }
            
            parsedLines.add(new LogLine(
                    LocalDateTime.parse(timestampString),
                    level,
                    matcher.group("className"),
                    content.toString()));
        }
        
        return parsedLines;
    }
}
