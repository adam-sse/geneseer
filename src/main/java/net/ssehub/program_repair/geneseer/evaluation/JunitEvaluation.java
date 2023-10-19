package net.ssehub.program_repair.geneseer.evaluation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

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
            try (TestExecution testExec = new TestExecution(workingDirectory, fullClasspath, false)) {
                for (String className : testClasses) {
                    runTestOrTimeout(workingDirectory, fullClasspath, executedTests, testExec, className);
                }
            }
            
            EvaluationResult result = new EvaluationResult();
            result.setExecutedTests(executedTests);

            return result;
        }
    }

    private void runTestOrTimeout(Path workingDirectory, List<Path> fullClasspath,
            List<TestResult> executedTests, TestExecution testExec, String className) throws EvaluationException {
        try {
            executedTests.addAll(testExec.executeTestClass(className));
            
        } catch (TimeoutException e) {
            executedTests.add(new TestResult(className, "<none>", "Timeout", "Timeout"));
        }
    }
    
}
