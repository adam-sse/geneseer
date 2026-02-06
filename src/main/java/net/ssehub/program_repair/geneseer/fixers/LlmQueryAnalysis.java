package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.ChangedArea;
import net.ssehub.program_repair.geneseer.llm.ChatGptRequest;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.llm.LlmFixer.CodeSnippet;

public class LlmQueryAnalysis implements IFixer {

    private static final Logger LOG = Logger.getLogger(LlmQueryAnalysis.class.getName());
    
    private Path projectRoot;
    
    private LlmFixer llmFixer;
    
    public LlmQueryAnalysis(Path projectRoot, LlmFixer llmFixer) {
        this.projectRoot = projectRoot;
        this.llmFixer = llmFixer;
    }

    @Override
    public Node run(Node original, TestSuite testSuite, Map<String, Object> result) throws IOException {
        Encoding tokenEncoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        
        List<CodeSnippet> codeSnippets = llmFixer.selectMostSuspiciousMethods(original);
        
        Path changedAreasFile = projectRoot.resolve("geneseer-changed-areas.json");
        java.lang.reflect.Type listType = new TypeToken<List<ChangedArea>>() {
        }.getType();
        List<ChangedArea> changedByHumanPatch = new Gson().fromJson(
                Files.readString(changedAreasFile, StandardCharsets.UTF_8), listType);
        
        List<ChangedArea> in = new LinkedList<>();
        List<ChangedArea> out = new LinkedList<>();
        Set<CodeSnippet> irrelevant = new HashSet<>();
        irrelevant.addAll(codeSnippets);
        
        for (ChangedArea area : changedByHumanPatch) {
            boolean containedByAny = false;
            for (CodeSnippet snippet : codeSnippets) {
                if (snippet.contains(area)) {
                    in.add(area);
                    irrelevant.remove(snippet);
                    containedByAny = true;
                }
            }
            
            if (!containedByAny) {
                out.add(area);
            }
        }
        
        result.put("patch", Map.of("hunksInQuery", in.size(), "hunksNotInQuery", out.size()));
        if (out.size() == 0) {
            result.put("result", "ALL_INCLUDED");
        } else if (in.size() == 0) {
            result.put("result", "NONE_INCLUDED");
        } else {    
            result.put("result", "PARTIALLY_INCLUDED");
        }
        ChatGptRequest query = llmFixer.createQuery(original,
                new ArrayList<>(testSuite.getInitialFailingTestResults()), codeSnippets);
        String queryText = query.getMessages().get(query.getMessages().size() - 1).getContent();
        LOG.info(() -> "Query:\n" + queryText);
        analyzeQuery(result, tokenEncoding, codeSnippets, irrelevant, queryText);
        
        Path queryJson = projectRoot.resolve("geneseer-llm-query.json");
        Files.write(queryJson, new Gson().toJson(query).getBytes(StandardCharsets.UTF_8));
        
        return null;
    }

    private void analyzeQuery(Map<String, Object> result, Encoding tokenEncoding,
            List<CodeSnippet> codeSnippets, Set<CodeSnippet> irrelevant, String query) {
        int relevantLines = codeSnippets.stream()
                .filter(s -> !irrelevant.contains(s))
                .mapToInt(s -> s.size()).sum();
        int irrelevantLines = irrelevant.stream()
                .mapToInt(s -> s.size())
                .sum();
        int relevantTokens = codeSnippets.stream()
                .filter(s -> !irrelevant.contains(s))
                .mapToInt(s -> tokenEncoding.countTokensOrdinary(s.getText()))
                .sum();
        int irrelevantTokens = irrelevant.stream()
                .mapToInt(s -> tokenEncoding.countTokensOrdinary(s.getText()))
                .sum();
        
        
        result.put("query", Map.of(
                "lines", query.split("\n").length,
                "tokens", tokenEncoding.countTokensOrdinary(query),
                "snippets", Map.of(
                        "relevant", codeSnippets.stream().filter(s -> !irrelevant.contains(s)).count(),
                        "irrelevant", irrelevant.size(),
                        "relevantLines", relevantLines,
                        "irrelevanLines", irrelevantLines,
                        "relevantTokens", relevantTokens,
                        "irrelevantTokens", irrelevantTokens)
                ));
    }

}
