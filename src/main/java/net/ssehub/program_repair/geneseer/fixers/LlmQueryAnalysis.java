package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import net.ssehub.program_repair.geneseer.Result;
import net.ssehub.program_repair.geneseer.Result.QueryStats.Snippets;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.defects4j.PatchWriter;
import net.ssehub.program_repair.geneseer.defects4j.PatchWriter.ChangedArea;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.CodeSnippet;
import net.ssehub.program_repair.geneseer.llm.LlmFixer;
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
    public Node run(Node original, TestSuite testSuite, Result result) throws IOException {
        Encoding tokenEncoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        List<TestResult> failingTests = testSuite.getInitialFailingTestResults(); 
        
        List<CodeSnippet> codeSnippets = llmFixer.selectMostSuspiciousMethods(original, failingTests);
        
        List<ChangedArea> changedByHumanPatch = JsonUtils.parseToListFromFile(
                projectRoot.resolve(PatchWriter.CHANGED_AREAS_FILENAME), ChangedArea.class);
        
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
        
        result.queryStats().setHumanPatchHunksInQuery(in.size());
        result.queryStats().setHumanPatchHunksNotInQuery(out.size());
        if (out.size() == 0) {
            result.setResult("ALL_INCLUDED");
        } else if (in.size() == 0) {
            result.setResult("NONE_INCLUDED");
        } else {    
            result.setResult("PARTIALLY_INCLUDED");
        }
        Query query = llmFixer.createQuery(original, failingTests, codeSnippets);
        String queryText = query.getMessages().get(query.getMessages().size() - 1).getContent();
        LOG.info(() -> "Query:\n" + queryText);
        analyzeQuery(result, tokenEncoding, codeSnippets, irrelevant, queryText);
        
        JsonUtils.writeJson(query, projectRoot.resolve(LLM_QUERY_FILENAME));
        return null;
    }

    private void analyzeQuery(Result result, Encoding tokenEncoding,
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
        
        result.queryStats().setLines(query.split("\n").length);
        result.queryStats().setTokens(tokenEncoding.countTokensOrdinary(query));
        
        result.queryStats().setSnippets(new Snippets(
                (int) codeSnippets.stream().filter(s -> !irrelevant.contains(s)).count(), irrelevant.size(),
                relevantLines, irrelevantLines,
                relevantTokens, irrelevantTokens
                ));
    }

}
