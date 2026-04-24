package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;

import net.ssehub.program_repair.geneseer.Result;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;

public interface IFixer {

    public Node run(Node ast, TestSuite testSuite, Result result) throws IOException;
    
}
