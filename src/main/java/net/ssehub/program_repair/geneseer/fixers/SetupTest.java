package net.ssehub.program_repair.geneseer.fixers;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.parsing.model.Node;

public class SetupTest implements IFixer {
    
    private static final Logger LOG = Logger.getLogger(SetupTest.class.getName());
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) {
        LOG.info(() -> testSuite.getInitialFailingTestResults().size() + " failing tests:");
        List<String> failingTests = new LinkedList<>();
        for (TestResult testResult : testSuite.getInitialTestResults()) {
            if (testResult.isFailure()) {
                LOG.info(() -> "    " + testResult + " " + testResult.failureMessage());
                failingTests.add(testResult.toString());
            }
        }
        result.put("failingTests", failingTests);
        if (failingTests.isEmpty()) {
            result.put("result", "NO_FAILING_TESTS");
        } else {
            result.put("result", "FOUND_FAILING_TESTS");
        }
        
        return null;
    }

}
