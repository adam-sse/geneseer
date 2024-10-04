package net.ssehub.program_repair.geneseer.util;

import com.google.gson.Gson;

public class JsonUtils {

    private static final Gson GSON = new Gson();
    
    private JsonUtils() {
    }
    
    public static String toJsonString(String string) {
        return GSON.toJson(string);
    }
    
}
