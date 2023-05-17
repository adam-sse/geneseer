package net.ssehub.program_repair.geneseer.evaluation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class JunitEvaluation extends AbstractTestExecution {

    private List<Path> classpath;
    
    private List<String> testClasses;
    
    public EvaluationResult runTests(Path workingDirectory, List<Path> classpath, Path classes, List<String> testClasses)
            throws EvaluationException {
        
        try (Probe probe = Measurement.INSTANCE.start("junit-evaluation")) {
            
            List<Path> fullClasspath = new ArrayList<>(classpath.size() + 1);
            fullClasspath.add(classes);
            fullClasspath.addAll(classpath);
            this.classpath = fullClasspath;
            
            this.testClasses = testClasses;
            
            List<TestResult> executedTests = executeTests(workingDirectory);
            
            EvaluationResult result = new EvaluationResult();
            result.setExecutedTests(executedTests);

            return result;
        }
    }

    @Override
    protected List<Path> getClasspath() {
        return classpath;
    }

    @Override
    protected String getMainClass() {
        return CLASS_LIST_RUNNER;
    }

    @Override
    protected List<String> getArguments() {
        return testClasses;
    }
    
    @Override
    protected boolean withJacoco() {
        return false;
    }

}
