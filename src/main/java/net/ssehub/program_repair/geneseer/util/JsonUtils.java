package net.ssehub.program_repair.geneseer.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

public class JsonUtils {

    private static final Gson GSON = new Gson();
    
    private JsonUtils() {
    }
    
    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }
    
    public static void writeJson(Object obj, Path file) throws IOException {
        Files.writeString(file, toJson(obj), StandardCharsets.UTF_8);
    }
    
    public static Map<String, Object> parseToMap(String json) throws JsonParseException {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = GSON.fromJson(json, Map.class);
        return result;
    }
    
    public static <T> List<T> parseToList(String json) throws JsonParseException {
        java.lang.reflect.Type listType = new TypeToken<List<T>>() {
        }.getType();
        return GSON.fromJson(json, listType);
    }
    
    public static <T> List<T> parseToListFromFile(Path file) throws IOException, JsonParseException {
        return parseToList(Files.readString(file, StandardCharsets.UTF_8));
    }
    
}
