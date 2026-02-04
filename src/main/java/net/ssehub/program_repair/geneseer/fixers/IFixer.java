package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.util.Map;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;

public interface IFixer {

    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) throws IOException;
    
}
