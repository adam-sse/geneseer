package net.ssehub.program_repair.geneseer.parsing;

import java.nio.file.Path;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.FileSystemFolder;

public class CodeModelFactory {

    private FileSystemFolder sourceDir;
    
    public CodeModelFactory(Path sourceDirectory) {
        this.sourceDir = new FileSystemFolder(sourceDirectory.toFile());
    }
    
    private Launcher createLauncher() {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourceDir);
        launcher.getEnvironment().setEncoding(Configuration.INSTANCE.getEncoding());
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
