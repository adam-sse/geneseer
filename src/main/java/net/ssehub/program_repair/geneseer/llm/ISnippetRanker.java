package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.util.List;

import net.ssehub.program_repair.geneseer.code.Node;

public interface ISnippetRanker {

    public List<CodeSnippet> selectCodeSnippets(Node code, List<TestMethodContext> failingTestMethods)
            throws IOException;
    
}
