package net.ssehub.program_repair.geneseer.fault_localization;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.log4j.LogManager;

import fr.spoonlabs.flacoco.api.result.FlacocoResult;
import fr.spoonlabs.flacoco.api.result.Suspiciousness;
import fr.spoonlabs.flacoco.core.config.FlacocoConfig;
import fr.spoonlabs.flacoco.core.config.FlacocoConfig.FaultLocalizationFamily;
import fr.spoonlabs.flacoco.core.test.method.TestMethod;
import fr.spoonlabs.flacoco.localization.spectrum.SpectrumFormula;
import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.EvaluationException;
import net.ssehub.program_repair.geneseer.evaluation.TestFailure;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtClass;

public class Flacoco {
    
    static {
        // configure log4j to not log (suppress flacoco output)
        LogManager.getRootLogger().setLevel(org.apache.log4j.Level.OFF);
    }

    private static final Logger LOG = Logger.getLogger(Flacoco.class.getName());
    
    private FlacocoConfig flacocoConfig;
    
    private Set<String> expectedFailures;
    
    public Flacoco(Path rootDirectory, List<Path> testExecutionClassPath) {
        flacocoConfig = new FlacocoConfig();
        flacocoConfig.setProjectPath(rootDirectory.toString());
        flacocoConfig.setWorkspace(rootDirectory.toString());
        
        flacocoConfig.setClasspath(testExecutionClassPath.stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)));
        
        flacocoConfig.setTestRunnerTimeoutInMs(Configuration.INSTANCE.getTestExecutionTimeoutMs());
        
        flacocoConfig.setFamily(FaultLocalizationFamily.SPECTRUM_BASED);
        flacocoConfig.setSpectrumFormula(SpectrumFormula.OCHIAI);
        
        flacocoConfig.setComputeSpoonResults(true);
    }
    
    public void setExpectedFailures(Collection<TestFailure> expectedFailures) {
        this.expectedFailures = expectedFailures.stream()
                .map(TestFailure::toString)
                .collect(Collectors.toSet());
    }
    
    public LinkedHashMap<CtStatement, Double> run(Path sourceDir, Path binDir) throws EvaluationException {
        try (Probe probe = Measurement.INSTANCE.start("flacoco")) {
            
            flacocoConfig.setBinJavaDir(List.of(binDir.toString()));
            flacocoConfig.setSrcJavaDir(List.of(sourceDir.toString()));
            
            fr.spoonlabs.flacoco.api.Flacoco flacoco = new fr.spoonlabs.flacoco.api.Flacoco(flacocoConfig);
            FlacocoResult flacocoResult = flacoco.run();
            
            LOG.fine(() -> flacocoResult.getFailingTests().size() + " failing tests detected by Flacoco: "
                    + flacocoResult.getFailingTests().stream()
                        .map(TestMethod::getFullyQualifiedMethodName)
                        .collect(Collectors.joining(", ")));
            
            if (expectedFailures != null) {
                compareExpectedFailures(flacocoResult.getFailingTests());
            }
            
            double totalSuspiciousness = flacocoResult.getSpoonSuspiciousnessMap().entrySet().stream()
                    .filter(e -> !(e.getKey() instanceof CtClass))
                    .filter(e -> !(e.getKey() instanceof CtBlock))
                    .mapToDouble(e -> e.getValue().getScore())
                    .sum();
            
            LinkedHashMap<CtStatement, Double> suspiciousness = new LinkedHashMap<>(
                    flacocoResult.getSpoonSuspiciousnessMap().size());
            
            Comparator<Map.Entry<CtStatement, Suspiciousness>> comparingScore
                    = Comparator.comparingDouble(e -> e.getValue().getScore());
            
            flacocoResult.getSpoonSuspiciousnessMap().entrySet().stream()
                    .filter(e -> !(e.getKey() instanceof CtClass))
                    .filter(e -> !(e.getKey() instanceof CtBlock))
                    .sorted(comparingScore.reversed())
                    .forEach(e -> suspiciousness.put(e.getKey(), e.getValue().getScore() / totalSuspiciousness));
            
            return suspiciousness;
        }
    }
    
    private void compareExpectedFailures(Set<TestMethod> flacocoFailures) throws EvaluationException {
        Set<String> actualFailingTests = flacocoFailures.stream()
            .map(TestMethod::getFullyQualifiedMethodName)
            .map(name -> name.replace("#", "::"))
            .collect(Collectors.toSet());
        
        if (!actualFailingTests.equals(expectedFailures)) {
            throw new EvaluationException("Flacoco failures are different from expected failures");
        }
    }
    
}
