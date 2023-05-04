package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Path;

import net.ssehub.program_repair.geneseer.parsing.CodeModel;
import net.ssehub.program_repair.geneseer.parsing.CodeModelFactory;

public class Test {
    
    public static void main(String[] args) throws IOException {
        CodeModelFactory codeModel = new CodeModelFactory(Path.of("testsource"));
        
        CodeModel model = codeModel.createModel();
        
        model.write(Path.of("testout"));
        
        System.out.println();
    }

}
