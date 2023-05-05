package net.ssehub.program_repair.geneseer.parsing;

import java.nio.file.Path;
import java.util.List;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.FileSystemFolder;

public class CodeModelFactory {

    private FileSystemFolder sourceDir;
    
    private List<Path> classpath;
    
    public CodeModelFactory(Path sourceDirectory, List<Path> classpath) {
        this.sourceDir = new FileSystemFolder(sourceDirectory.toFile());
        this.classpath = classpath;
    }
    
    private Launcher createLauncher() {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourceDir);
        launcher.getEnvironment().setEncoding(Configuration.INSTANCE.getEncoding());
        launcher.getEnvironment().setSourceClasspath(this.classpath.stream()
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .toArray(size -> new String[size]));
//        launcher.getEnvironment().setPreserveLineNumbers(true);
        return launcher;
    }
    
    public CodeModel createModel() {
        try (Probe probe = Measurement.INSTANCE.start("spoon-parsing")) {
            Launcher spoonLauncher = createLauncher();
            CtModel model = spoonLauncher.buildModel();
            return new CodeModel(spoonLauncher, model, Path.of(sourceDir.getPath()));
        }
    }
    
}
