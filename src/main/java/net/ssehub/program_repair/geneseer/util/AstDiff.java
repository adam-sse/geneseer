package net.ssehub.program_repair.geneseer.util;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

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
        
        return getDiff(aDir, bDir, encoding, null);
    }
    
    public static String getDiff(Path aDir, Path bDir, Charset encoding, Integer context) throws IOException {
        List<String> command = new LinkedList<>(List.of("git", "diff", "--no-index",
                aDir.toString(), bDir.toString()));
        if (context != null) {
            command.add("--unified=" + context);
        }
        
        ProcessRunner diffProcess = new ProcessRunner.Builder(command)
                .captureOutput(true)
                .run();
        
        String diff = new String(diffProcess.getStdout())
                .replace("a" + aDir, "a")
                .replace("b" + bDir, "b");
        
        return diff;
    }
    
}
