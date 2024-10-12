package net.ssehub.program_repair.geneseer.evaluation;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class JunitEvaluation {
    
    private Path workingDirectory;
    
    private List<Path> classpath;
    
    private Charset encoding;
    
    public JunitEvaluation(Path workingDirectory, List<Path> classpath, Charset encoding) {
        this.workingDirectory = workingDirectory;
        this.classpath = classpath;
        this.encoding = encoding;
    }

    public EvaluationResult runTests(Path classes, List<String> testClasses) throws TestExecutionException {
        
        try (Probe probe = Measurement.INSTANCE.start("junit-evaluation")) {
            
            List<Path> fullClasspath = new ArrayList<>(classpath.size() + 1);
            fullClasspath.add(classes);
            fullClasspath.addAll(classpath);
            
            List<TestResult> executedTests = new LinkedList<>();
            try (TestExecution testExec = new TestExecution(workingDirectory, fullClasspath, encoding, false)) {
                testExec.setTimeout(Configuration.INSTANCE.setup().testExecutionTimeoutMs());
                for (String className : testClasses) {
                    runTestCatchingTimeout(workingDirectory, fullClasspath, executedTests, testExec, className);
                }
            }
            
            EvaluationResult result = new EvaluationResult();
            result.setExecutedTests(executedTests);

            return result;
        }
    }

    private void runTestCatchingTimeout(Path workingDirectory, List<Path> fullClasspath,
            List<TestResult> executedTests, TestExecution testExec, String className) throws TestExecutionException {
        try {
            executedTests.addAll(testExec.executeTestClass(className));
            
        } catch (TestTimeoutException e) {
            executedTests.add(new TestResult(className, "<none>", "Timeout", "Timeout"));
        }
    }
    
}
