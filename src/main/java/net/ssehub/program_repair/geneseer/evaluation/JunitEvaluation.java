package net.ssehub.program_repair.geneseer.evaluation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class JunitEvaluation {

    public EvaluationResult runTests(Path workingDirectory, List<Path> classpath, Path classes,
            List<String> testClasses) throws EvaluationException {
        
        try (Probe probe = Measurement.INSTANCE.start("junit-evaluation")) {
            
            List<Path> fullClasspath = new ArrayList<>(classpath.size() + 1);
            fullClasspath.add(classes);
            fullClasspath.addAll(classpath);
            
            List<TestResult> executedTests = new LinkedList<>();
            TestExecution testExec = null;
            try {
                testExec = new TestExecution(workingDirectory, fullClasspath, false);
                testExec.setTimeout(Configuration.INSTANCE.getTestExecutionTimeoutMs());
                
                for (String className : testClasses) {
                    try {
                        executedTests.addAll(testExec.executeTestClass(className));
                        
                    } catch (TimeoutException e) {
                        executedTests.add(new TestResult(className, "<none>", "Timeout", "Timeout"));
                        
                        testExec.close();
                        testExec = new TestExecution(workingDirectory, fullClasspath, false);
                        testExec.setTimeout(Configuration.INSTANCE.getTestExecutionTimeoutMs());
                    }
                }
            } finally {
                if (testExec != null) {
                    testExec.close();
                }
            }
            
            EvaluationResult result = new EvaluationResult();
            result.setExecutedTests(executedTests);

            return result;
        }
    }

}
