package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.llm.CodeSnippet.LineRange;

public abstract class AbstractMethodRanker implements ISnippetRanker {

    private int lineLimit;
    
    private double lastCutoff;
    
    public AbstractMethodRanker(int lineLimit) {
        this.lineLimit = lineLimit;
    }
    
    @Override
    public final List<CodeSnippet> selectCodeSnippets(Node code, List<TestMethodContext> failingTestMethods)
            throws IOException {
        
        Set<String> failingTestIdentifiers = failingTestMethods.stream()
                .map(TestMethodContext::testResult)
                .map(TestResult::getIdentifier)
                .collect(Collectors.toUnmodifiableSet());
        
        List<CodeSnippet> selectedMethods = new LinkedList<>();
        int codeSize = 0;
        for (Map.Entry<Node, Double> entry : rankMethods(code, failingTestMethods).entrySet()) {
            LineRange range = LineRange.getRange(code, entry.getKey());
            
            if (codeSize + range.size() < lineLimit) {
                CodeSnippet snippet = CodeSnippet.getSnippetForMethod(code, entry.getKey());
                @SuppressWarnings("unchecked")
                Set<String> methodCoveredBy = (Set<String>) entry.getKey().getMetadata(Metadata.COVERED_BY);
                if (methodCoveredBy != null) {
                    int failing = (int) methodCoveredBy.stream()
                            .filter(failingTestIdentifiers::contains)
                            .count();
                    int passing = methodCoveredBy.size() - failing;
                    snippet.setTestCoverage(passing, failing);
                }
                
                selectedMethods.add(snippet);
                codeSize += range.size();
                lastCutoff = entry.getValue();
            } else {
                break;
            }
        }
        return selectedMethods;
    }
    
    public abstract LinkedHashMap<Node, Double> rankMethods(Node code, List<TestMethodContext> failingTestMethods)
            throws IOException;
    
    protected int getLineLimit() {
        return lineLimit;
    }
    
    public double getLastCutoff() {
        return lastCutoff;
    }

}
