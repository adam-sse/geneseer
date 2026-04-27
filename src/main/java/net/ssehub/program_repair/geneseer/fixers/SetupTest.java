package net.ssehub.program_repair.geneseer.fixers;

import java.util.List;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.Result;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;

public class SetupTest implements IFixer {
    
    private static final Logger LOG = Logger.getLogger(SetupTest.class.getName());
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Result result) {
        List<TestResult> failingTests = testSuite.getInitialFailingTestResults();
        LOG.info(() -> failingTests.size() + " failing tests:");
        for (TestResult testResult : failingTests) {
            LOG.info(() -> "    * " + testResult + "\n" + testResult.failureStacktrace());
        }
        if (failingTests.isEmpty()) {
            result.setResult("NO_FAILING_TESTS");
        } else {
            result.setResult("FOUND_FAILING_TESTS");
        }
        
        return null;
    }

}
