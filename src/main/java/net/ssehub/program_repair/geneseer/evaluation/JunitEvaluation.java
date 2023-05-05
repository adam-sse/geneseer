package net.ssehub.program_repair.geneseer.evaluation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import eu.stamp_project.testrunner.EntryPoint;
import eu.stamp_project.testrunner.listener.TestResult;
import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class JunitEvaluation {

    private static final Logger LOG = Logger.getLogger(JunitEvaluation.class.getName());
    
    public EvaluationResult runTests(Path workingDirectory, List<Path> classpath, Path classes, List<String> testClasses)
            throws EvaluationException {
        
        try (Probe probe = Measurement.INSTANCE.start("junit-evaluation")) {
            
            StringBuilder cp = new StringBuilder();
            cp.append(classes.toString());
            for (Path element : classpath) {
                cp.append(File.pathSeparatorChar);
                cp.append(element.toString());
            }
            
            EntryPoint.timeoutInMs = Configuration.INSTANCE.getTestExecutionTimeoutMs();
            EntryPoint.workingDirectory = workingDirectory.toFile();
            EntryPoint.JVMArgs = "-Dfile.encoding=" + Configuration.INSTANCE.getEncoding();
            
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            EntryPoint.outPrintStream = new PrintStream(captured);
            EntryPoint.verbose = true;
            
            try {
                TestResult testRunnerResult = EntryPoint.runTests(
                        cp.toString(), testClasses.toArray(String[]::new));
                
                EvaluationResult result = new EvaluationResult();
                result.setFailures(testRunnerResult.getFailingTests().stream()
                    .map(TestFailure::new)
                    .toList());
                
                LOG.info(() -> result.getFailures().size() + " test failures");
                for (TestFailure failure : result.getFailures()) {
                    LOG.fine(() -> "Failure: " + failure.toString() + " " + failure.message());
                }
                
                return result;
                
            } catch (TimeoutException e) {
                throw new EvaluationException("Running tests timed out", e);
                
            } finally {
                resetEntryPoint();
                
                String output = captured.toString();
                if (!output.isEmpty()) {
                    LOG.fine(() -> "Test output:\n" + output);
                }
            }
        }
    }
    
    public static void resetEntryPoint() {
        EntryPoint.JVMArgs = null;
        EntryPoint.workingDirectory = null;
        EntryPoint.timeoutInMs = Configuration.INSTANCE.getTestExecutionTimeoutMs();
        EntryPoint.outPrintStream = null;
        EntryPoint.errPrintStream = null;
        EntryPoint.blackList.clear();
        EntryPoint.jacocoAgentIncludes = null;
        EntryPoint.jacocoAgentExcludes = null;
    }

}
