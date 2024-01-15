package net.ssehub.program_repair.geneseer.parsing;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Stack;

import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class Writer {

    private Writer() {
    }
    
    public static void write(Node parseTree, Path sourceDirectory, Path outputDirectory, Charset encoding)
            throws IOException {
        try (Probe probe = Measurement.INSTANCE.start("code-writing")) {
            
            for (Node compilationUnit : parseTree.childIterator()) {
                Path file = outputDirectory.resolve((Path) compilationUnit.getMetadata(Metadata.FILENAME));
                Files.createDirectories(file.getParent());
                
                Files.writeString(file, toText(compilationUnit), encoding);
            }
            
            if (sourceDirectory != null) {
                FileUtils.copyAllNonJavaSourceFiles(sourceDirectory, outputDirectory);
            }
        }
    }
    
    private static String toText(Node root) {
        int currentLine = 1;
        int currentColumn = 0;
        
        Stack<Node> nodes = new Stack<>();
        nodes.push(root);
        
        StringBuilder str = new StringBuilder();
        
        while (!nodes.isEmpty()) {
            Node currentNode = nodes.pop();
            
            if (currentNode instanceof LeafNode leaf) {
                if (leaf.getOriginalPosition() != null) {
                    while (leaf.getOriginalPosition().line() > currentLine) {
                        str.append('\n');
                        currentLine++;
                        currentColumn = 0;
                    }
                    while (leaf.getOriginalPosition().column() > currentColumn) {
                        str.append(' ');
                        currentColumn++;
                    }
                    
                } else {
                    str.append(' ');
                }
                
                String text = leaf.getText();
                currentColumn += text.length();
                str.append(text);
                
            } else {
                for (int i = currentNode.childCount() - 1; i >= 0; i--) {
                    nodes.push(currentNode.get(i));
                }
            }
        }
        
        return str.toString();
    }
    
}
