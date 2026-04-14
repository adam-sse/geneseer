package net.ssehub.program_repair.geneseer.fixers;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;

public class SetupTest implements IFixer {
    
    private static final Logger LOG = Logger.getLogger(SetupTest.class.getName());
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) {
        List<TestResult> failingTests = testSuite.getInitialFailingTestResults();
        LOG.info(() -> failingTests.size() + " failing tests:");
        for (TestResult testResult : failingTests) {
            LOG.info(() -> "    * " + testResult + "\n" + testResult.failureStacktrace());
        }
        result.put("failingTests", failingTests.stream().map(TestResult::toString).toList());
        if (failingTests.isEmpty()) {
            result.put("result", "NO_FAILING_TESTS");
        } else {
            result.put("result", "FOUND_FAILING_TESTS");
        }
        
        return null;
    }

}
