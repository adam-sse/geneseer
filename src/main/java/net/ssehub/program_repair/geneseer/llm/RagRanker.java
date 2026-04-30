package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.llm.rag.ChromaDb;
import net.ssehub.program_repair.geneseer.llm.rag.ChromaDb.Method;
import net.ssehub.program_repair.geneseer.llm.rag.ChromaDb.MethodWithSimilarity;

public class RagRanker extends AbstractMethodRanker {
    
    private static final Logger LOG = Logger.getLogger(RagRanker.class.getName());
    
    private Path projectRoot;
    
    private String model;
    
    private URL api;
    
    public RagRanker(Path projectRoot, int lineLimit, String model, URL api) throws IllegalArgumentException {
        super(lineLimit);
        this.projectRoot = projectRoot;
        if (model == null) {
            throw new IllegalArgumentException("RAG embedding model not set");
        }
        this.model = model;
        if (api == null) {
            throw new IllegalArgumentException("RAG embedding API not set");
        }
        this.api = api;
        
        if (Configuration.INSTANCE.rag().chromadbWorkerPythonBinaryPath() == null) {
            throw new IllegalArgumentException("Path to python for chromadb-worker.py script not set");
        }
    }
    
    @Override
    public LinkedHashMap<Node, Double> rankMethods(Node code, List<TestMethodContext> failingTestMethods)
            throws IOException {
        try (ChromaDb db = new ChromaDb(projectRoot, model, api, Configuration.INSTANCE.rag().persist())) {
            List<Method> allMethods = code.stream()
                    .filter(n -> n.getType() == Type.METHOD || n.getType() == Type.CONSTRUCTOR)
                    .map(m -> {
                        String className = AstUtils.getEnclosingClass(code, m);
                        if (className == null) {
                            className = "<none>";
                        }
                        return new Method(m.getTextFormatted(),
                                AstUtils.getSignature(m), className,
                                AstUtils.getFile(code, m), AstUtils.getLine(code, m),
                                m);
                    })
                    .toList();
            
            int existing = db.getEntryCount();
            if (existing != allMethods.size()) {
                if (existing > 0) {
                    LOG.info("Clearing existing database because entry count does not match");
                    db.clear();
                }
                LOG.info(() -> "Storing " + allMethods.size() + " entries in database");
                db.storeMethods(allMethods);
            } else {
                LOG.info("Existing database size matches, reusing...");
            }

            Map<Node, Double> methodDistances = new HashMap<>();
            for (TestMethodContext testMethod : failingTestMethods) {
                if (testMethod.code() != null) {
                    for (MethodWithSimilarity method : db.query(testMethod.code(), getLineLimit(), allMethods)) {
                        if (!methodDistances.containsKey(method.ast())
                                || methodDistances.get(method.ast()) > method.distance()) {
                            methodDistances.put(method.ast(), method.distance());
                        }
                    }
                }
            }
            
            LinkedHashMap<Node, Double> sortedSuspiciousness = new LinkedHashMap<>(methodDistances.size());
            methodDistances.entrySet().stream()
                    .sorted((e1, e2) -> Double.compare(e1.getValue(), e2.getValue())) // ascending distances
                    .forEach(e -> sortedSuspiciousness.put(e.getKey(), e.getValue()));
            return sortedSuspiciousness;
        }
    }

}
