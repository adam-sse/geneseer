package net.ssehub.program_repair.geneseer.llm.rag;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.util.JsonUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import net.ssehub.program_repair.geneseer.util.ProcessManager;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class ChromaDb implements Closeable {
    
    private static final Logger LOG = Logger.getLogger(ChromaDb.class.getName());
    
    private static final Path CHROMADB_WORKER_PATH;

    static {
        try {
            @SuppressWarnings("resource") // closed in shutdown hook of TemporaryDirectoryManager
            TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager();
            Path tempDir = tempDirManager.createTemporaryDirectory();

            CHROMADB_WORKER_PATH = tempDir.resolve("chromadb-worker.py");

            Files.write(CHROMADB_WORKER_PATH, ChromaDb.class.getClassLoader()
                    .getResourceAsStream("net/ssehub/program_repair/geneseer/llm/rag/chromadb-worker.py")
                    .readAllBytes());

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to create temporary directory with chromadb-worker script", e);
            throw new UncheckedIOException(e);
        }
    }
    
    public record Method(String code, String signature, String className, Path file, int line, Node ast) {
    }
    
    public record MethodWithSimilarity(Node ast, double distance) {
    }
    
    private String model;
    
    private URL api;
    
    private Process process;
    
    private BufferedReader in;
    
    private PrintWriter out;
    
    public ChromaDb(Path projectRoot, String model, URL api, boolean persistent) throws IOException {
        this.model = model;
        this.api = api;
        startProcess(projectRoot, persistent);
    }
    
    private void startProcess(Path projectRoot, boolean persistent) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Configuration.INSTANCE.rag().chromadbWorkerPythonBinaryPath(),
                CHROMADB_WORKER_PATH.toAbsolutePath().toString(),
                "--model", model,
                "--host", api.toString(),
                persistent ? "--persistent" : "--no-persistent");
        builder.redirectInput(Redirect.PIPE);
        builder.redirectOutput(Redirect.PIPE);
        builder.redirectErrorStream(true);
        builder.directory(projectRoot.toFile());
        LOG.fine(() -> "Starting process " + builder.command());
        process = builder.start();
        ProcessManager.INSTANCE.trackProcess(process);
        in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
    }
    
    public void storeMethods(List<Method> methods) throws IOException {
        try (Probe measure = Measurement.INSTANCE.start("rag-build")) {
            Map<String, Object> addAction = new HashMap<>();
            addAction.put("action", "add");
            addAction.put("methods", methods.stream()
                    .map(m -> Map.of(
                            "code", m.code(),
                            "signature", m.signature(),
                            "class", m.className(),
                            "file", m.file().toString(),
                            "line", m.line()))
                    .toList());
            writeCommand(addAction);
        }
    }
    
    public List<MethodWithSimilarity> query(String prompt, int numResults, List<Method> methods)
            throws IOException {
        try (Probe measure = Measurement.INSTANCE.start("rag-query")) {
            Map<String, Object> queryAction = new HashMap<>();
            queryAction.put("action", "query");
            queryAction.put("prompt", prompt);
            queryAction.put("n_results", numResults);
            
            Map<String, Object> response = writeCommand(queryAction);
            
            List<Object> distances = requireListOfListWithSingleEntry(response, "distances");
            List<Object> metadatas = requireListOfListWithSingleEntry(response, "metadatas");
            
            if (distances.size() != metadatas.size()) {
                throw new IOException("Lists \"distances\" and \"metadatas\" don't match in size: " + response);
            }
            
            List<MethodWithSimilarity> result = new LinkedList<>();
            for (int i = 0; i < distances.size(); i++) {
                if (!(distances.get(i) instanceof Number)) {
                    throw new IOException("Invalid distance \"" + distances.get(i) + "\"");
                }
                if (!(metadatas.get(i) instanceof Map)) {
                    throw new IOException("Invalid metadata \"" + metadatas.get(i) + "\"");
                }
                double distance = ((Number) distances.get(i)).doubleValue();
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) metadatas.get(i);
                String signature = Objects.toString(metadata.get("signature"));
                String className = Objects.toString(metadata.get("class"));
                Path file = Path.of(Objects.toString(metadata.get("file")));
                int line = Integer.parseInt(Objects.toString(metadata.get("line")).replace(".0", ""));
                
                List<Method> matching = methods.stream()
                        .filter(m -> m.signature().equals(signature)
                                && m.className().equals(className)
                                && m.file().equals(file)
                                && m.line() == line)
                        .toList();
                if (matching.size() != 1) {
                    throw new IOException("Got " + matching.size() + " matching methods for signature " + signature
                            + " in class " + className);
                }
                
                result.add(new MethodWithSimilarity(matching.get(0).ast(), distance));
            }
            
            return result;
        }
    }
    
    private static List<Object> requireListOfListWithSingleEntry(Map<String, Object> response, String key)
            throws IOException {
        if (!response.containsKey(key)) {
            throw new IOException("Field \"" + key + "\" missing: " + response);
        }
        if (!(response.get(key) instanceof List)) {
            throw new IOException("Field \"" + key + "\" is not a list: " + response);
        }
        @SuppressWarnings("unchecked")
        List<Object> outerList = (List<Object>) response.get(key);
        if (outerList.size() != 1) {
            throw new IOException("Expected exactly one element in list \"" + key + "\", but got " + outerList.size()
                    + ": " + outerList);
        }
        if (!(outerList.get(0) instanceof List)) {
            throw new IOException("Element in list \"" + key + "\" is not a list: " + outerList);
        }
        @SuppressWarnings("unchecked")
        List<Object> innerList = (List<Object>) outerList.get(0);
        return innerList;
    }
    
    public int getEntryCount() throws IOException {
        Map<String, Object> action = new HashMap<>();
        action.put("action", "entry_count");
        Map<String, Object> response = writeCommand(action);
        if (!response.containsKey("entry_count") || !(response.get("entry_count") instanceof Number)) {
            throw new IOException("Invalid entry_count in response: " + response);
        }
        return ((Number) response.get("entry_count")).intValue();
    }
    
    public void clear() throws IOException {
        Map<String, Object> action = new HashMap<>();
        action.put("action", "clear");
        writeCommand(action);
    }

    @Override
    public void close() throws IOException {
        process.destroy();
    }
    
    private Map<String, Object> writeCommand(Map<String, Object> command) throws IOException {
        String commandJson = JsonUtils.toJson(command);
        LOG.finer(() -> "Sending command: " + commandJson);
        out.println(commandJson);
        
        String responseJson = in.readLine();
        LOG.finer(() -> "Got response: " + responseJson);
        try {
            Map<String, Object> response = JsonUtils.parseToMap(responseJson);
            if (!"ok".equals(response.get("status"))) {
                throw new IOException("Got non-ok status \"" + response.get("status") + "\" in response: " + response);
            }
            return response;
        } catch (JsonParseException e) {
            throw new IOException("Got invalid JSON: " + responseJson, e);
        }
    }
    
}
