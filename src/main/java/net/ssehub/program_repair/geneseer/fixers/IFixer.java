package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.util.Map;

import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.parsing.model.Node;

public interface IFixer {

    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) throws IOException;
    
}
