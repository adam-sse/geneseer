package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.ssehub.program_repair.geneseer.parsing.CodeModel;
import net.ssehub.program_repair.geneseer.parsing.CodeModelFactory;
import net.ssehub.program_repair.geneseer.util.FileUtils;

public class ParseAndWrite {

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: <input directory> <output directory>");
            System.exit(1);
        }
        
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        
        if (Files.isDirectory(out)) {
            FileUtils.deleteDirectory(out);
        }
        Files.createDirectory(out);
        
        CodeModelFactory factory = new CodeModelFactory(in, List.of());
        CodeModel model = factory.createModel();
        System.out.println("Parsed model with " + model.getSpoonModel().getAllTypes().size() + " types");
        
        model.write(out);
        System.out.println("Wrote model to " + out.toAbsolutePath());
    }
    
}
