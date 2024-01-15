package net.ssehub.program_repair.geneseer.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.Node;

public class AstDiff {
    
    private AstDiff() {
    }

    public static String getDiff(Node ast1, Node ast2, TemporaryDirectoryManager tempDirManager, Charset encoding)
            throws IOException {
        
        Path aDir = tempDirManager.createTemporaryDirectory();
        Path bDir = tempDirManager.createTemporaryDirectory();
        
        Writer.write(ast1, null, aDir, encoding);
        Writer.write(ast2, null, bDir, encoding);
        
        ProcessRunner diffProcess = new ProcessRunner.Builder("git", "diff", "--no-index",
                aDir.toString(), bDir.toString())
                .captureOutput(true)
                .run();
        
        String diff = new String(diffProcess.getStdout())
                .replace("a/" + aDir.toString().replace("\\", "\\\\"), "a")
                .replace("b/" + bDir.toString().replace("\\", "\\\\"), "b");
        
        return diff;
    }
    
}
