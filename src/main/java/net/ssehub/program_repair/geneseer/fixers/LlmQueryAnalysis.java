package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.defects4j.PatchWriter;
import net.ssehub.program_repair.geneseer.defects4j.PatchWriter.ChangedArea;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
import net.ssehub.program_repair.geneseer.llm.LlmFixer.CodeSnippet;
import net.ssehub.program_repair.geneseer.llm.Query;
import net.ssehub.program_repair.geneseer.util.JsonUtils;

public class LlmQueryAnalysis implements IFixer {

    public static final String LLM_QUERY_FILENAME = "geneseer-llm-query.json";
    
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
        
        List<ChangedArea> changedByHumanPatch = JsonUtils.parseToListFromFile(
                projectRoot.resolve(PatchWriter.CHANGED_AREAS_FILENAME));
        
        List<ChangedArea> in = new LinkedList<>();
        List<ChangedArea> out = new LinkedList<>();
        Set<CodeSnippet> irrelevant = new HashSet<>();
        irrelevant.addAll(codeSnippets);
        
        for (ChangedArea area : changedByHumanPatch) {
            boolean containedByAny = false;
            for (CodeSnippet snippet : codeSnippets) {
                if (area.isWithin(snippet)) {
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
        Query query = llmFixer.createQuery(original,
                new ArrayList<>(testSuite.getInitialFailingTestResults()), codeSnippets);
        String queryText = query.getMessages().get(query.getMessages().size() - 1).getContent();
        LOG.info(() -> "Query:\n" + queryText);
        analyzeQuery(result, tokenEncoding, codeSnippets, irrelevant, queryText);
        
        JsonUtils.writeJson(query, projectRoot.resolve(LLM_QUERY_FILENAME));
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
