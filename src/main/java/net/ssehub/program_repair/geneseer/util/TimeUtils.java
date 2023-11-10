package net.ssehub.program_repair.geneseer.util;

import java.util.Locale;

public class TimeUtils {

    private TimeUtils() {
    }
    
    public static String formatSeconds(long seconds) {
        StringBuilder str = new StringBuilder();
        long minutes = seconds / 60;
        seconds %= 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes %= 60;
            
            str.append(hours).append(':');
        }
        
        str.append(String.format(Locale.ROOT, "%02d:%02d", minutes, seconds));
        
        return str.toString();
    }
    
    public static String formatMilliseconds(long millis) {
        return String.format(Locale.ROOT, "%s.%03d", formatSeconds(millis / 1000), millis % 1000);
    }
    
}
