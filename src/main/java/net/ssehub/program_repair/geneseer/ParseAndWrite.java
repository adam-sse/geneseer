package net.ssehub.program_repair.geneseer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;

import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
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
        
        Node ast = Parser.parse(in);
        System.out.println("Parsed ast with " + ast.children().size() + " files");
        
        Writer.write(ast, in, out);
        System.out.println("Wrote model to " + out.toAbsolutePath());
        
//        System.out.println(model.getParseTree().children().get(0).dumpTree());

        
        System.out.println("### Single statements ###");
        List<Node> statements = new LinkedList<>();
        ast.stream()
            .filter(n -> n.getType() == Type.SINGLE_STATEMENT)
            .peek(statements::add)
            .forEach(n -> {
                List<LeafNode> leafNodes = n.stream().filter(l -> l.getType() == Type.LEAF).map(l -> (LeafNode) l).toList();
                int minLine = -1;
                int maxLine = -1;
                for (LeafNode leaf : leafNodes) {
                    if (leaf.getOriginalPosition() != null) {
                        if (leaf.getOriginalPosition().line() < minLine || minLine == -1) {
                            minLine = leaf.getOriginalPosition().line();
                        }
                        if (leaf.getOriginalPosition().line() > maxLine || maxLine == -1) {
                            maxLine = leaf.getOriginalPosition().line();
                        }
                    }
                }
                
                if (minLine == maxLine) {
                    System.out.print("[" + minLine + "] ");
                } else  {
                    System.out.print("[" + minLine + "-" + maxLine + "] ");
                }
                
                System.out.println(n.getText());
            });
    }
    
}
