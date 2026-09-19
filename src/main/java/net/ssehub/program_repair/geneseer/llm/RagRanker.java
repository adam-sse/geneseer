package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.llm.rag.ChromaDb;
import net.ssehub.program_repair.geneseer.llm.rag.ChromaDb.Method;

public class RagRanker extends AbstractMethodRanker {

    private static final Logger LOG = Logger.getLogger(RagRanker.class.getName());
    
    private Path projectRoot;
    
    private ILlm llm;
    
    private String embeddingModel;
    
    private URL embeddingApi;
    
    public RagRanker(Path projectRoot, int lineLimit, ILlm llm, String ragModel, URL ragApi)
            throws IllegalArgumentException {
        super(lineLimit);
        
        this.projectRoot = projectRoot;
        this.llm = llm;
        
        if (ragModel == null) {
            throw new IllegalArgumentException("RAG embedding model not set");
        }
        this.embeddingModel = ragModel;
        if (ragApi == null) {
            throw new IllegalArgumentException("RAG embedding API not set");
        }
        this.embeddingApi = ragApi;
        
        if (Configuration.INSTANCE.rag().chromadbWorkerPythonBinaryPath() == null) {
            throw new IllegalArgumentException("Path to python for chromadb-worker.py script not set");
        }
    }

    @Override
    public LinkedHashMap<Node, Double> rankMethods(Node code, List<TestMethodContext> failingTestMethods)
            throws IOException {
        
        Set<String> failingTestMethodIdentifiers = failingTestMethods.stream()
                .map(TestMethodContext::testResult)
                .map(TestResult::getIdentifier)
                .collect(Collectors.toUnmodifiableSet());
        
        @SuppressWarnings("unchecked")
        List<Method> methodsCoveredByFailingTests = code.stream()
                .filter(n -> n.getType() == Type.METHOD || n.getType() == Type.CONSTRUCTOR)
                .filter(n -> !Collections.disjoint(
                        (Set<String>) n.getMetadata(Metadata.COVERED_BY), failingTestMethodIdentifiers))
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
        
        try (ChromaDb db = new ChromaDb(projectRoot, embeddingModel, embeddingApi, false)) {
            LOG.fine(() -> "Adding " + methodsCoveredByFailingTests.size()
                    + " methods covered by failing tests to ChromaDB");
            db.storeMethods(methodsCoveredByFailingTests);
            
            String query = functionalityExtraction(failingTestMethods);
            
            LinkedHashMap<Node, Double> sortedSuspiciousness = new LinkedHashMap<>(methodsCoveredByFailingTests.size());
            db.query(query, getLineLimit(), methodsCoveredByFailingTests).stream()
                    .sorted((m1, m2) -> Double.compare(m1.distance(), m2.distance())) // ascending distances
                    .forEach(m -> sortedSuspiciousness.put(m.ast(), m.distance()));
            
            return sortedSuspiciousness;
        }
    }
    
    private String functionalityExtraction(List<TestMethodContext> failingTestMethods) throws IOException {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Your task is to identify faulty program behavior. One or more unit tests"
                + " have failed due to the same underlying functionality issue. Given the following test failure"
                + " information (including multiple test codes, and stack traces), extract **only** the underlying"
                + " functional logic that failed. Your output should be a clean, concise description of the shared"
                + " functionality that failed to be implemented correctly.\n"
                + "\n"
                + "Requirements:\n"
                + "- Focus on what functionality failed, not how the tests failed.\n"
                + "- Include any relevant objects, inputs, and expected behavior if available.\n"
                + "- The description should be precise and suitable for use as a semantic query to retrieve code"
                + " (in natural language).\n"
                + "- Avoid unrelated details.\n\n");
        
        AbstractLlmMutator.writeFailingTestCases(prompt, failingTestMethods);
        
        LOG.fine(() -> "Prompt for functionality extraction:\n" + prompt);
        Query query = new Query();
        query.addMessage(new Message(Role.SYSTEM,
                "You are a code assistant helping to identify faulty program behavior."));
        query.addMessage(new Message(Role.USER, prompt.toString()));
        
        IResponse response = llm.send(query);
        String functionalityDescription = response.getContent();
        LOG.info(() -> "Functionality description of failing test cases: " + functionalityDescription);
        
        return functionalityDescription;
    }

}
