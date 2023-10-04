package net.ssehub.program_repair.geneseer.parsing;

import java.io.IOException;
import java.nio.file.Path;

import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.factory.Factory;
import spoon.support.JavaOutputProcessor;

public class CodeModel {

    private Launcher spoonLauncher;
    
    private CtModel model;
    
    private Path sourceBaseDirectory;
    
    CodeModel(Launcher spoonLauncher, CtModel model, Path sourceBaseDirectory) {
        this.spoonLauncher = spoonLauncher;
        this.model = model;
        this.sourceBaseDirectory = sourceBaseDirectory;
    }
    
    public CtModel getSpoonModel() {
        return this.model;
    }
    
    public Factory getFactory() {
        return spoonLauncher.getFactory();
    }
    
    public void write(Path outputDirectory) throws IOException {
        try (Probe probe = Measurement.INSTANCE.start("spoon-writing")) {
            spoonLauncher.setSourceOutputDirectory(outputDirectory.toFile());
            JavaOutputProcessor fileWriter = spoonLauncher.createOutputWriter();
            model.processWith(fileWriter);
            
            FileUtils.copyAllNonJavaSourceFiles(sourceBaseDirectory, outputDirectory);
        }
    }
    
}
