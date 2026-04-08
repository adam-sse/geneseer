package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.ssehub.program_repair.geneseer.code.Node;
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
        
        List<CodeSnippet> selectedMethods = new LinkedList<>();
        int codeSize = 0;
        for (Map.Entry<Node, Double> entry : rankMethods(code, failingTestMethods).entrySet()) {
            LineRange range = LineRange.getRange(code, entry.getKey());
            
            if (codeSize + range.size() < lineLimit) {
                selectedMethods.add(CodeSnippet.getSnippetForMethod(code, entry.getKey()));
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
