package net.ssehub.program_repair.geneseer.parsing;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Stack;

import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;

public class Writer {

    private Writer() {
    }
    
    public static void write(Node parseTree, Path outputDirectory, Charset encoding) throws IOException {
        for (Node compilationUnit : parseTree.childIterator()) {
            writeSingleFile(compilationUnit, outputDirectory, encoding);
        }
    }
    
    public static void writeSingleFile(Node singleFileAst, Path outputDirectory, Charset encoding) throws IOException {
        Path file = outputDirectory.resolve((Path) singleFileAst.getMetadata(Metadata.FILE_NAME));
        Files.createDirectories(file.getParent());
        
        Files.writeString(file, toText(singleFileAst), encoding);
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
                while (leaf.getPosition().line() > currentLine) {
                    str.append('\n');
                    currentLine++;
                    currentColumn = 0;
                }
                while (leaf.getPosition().column() > currentColumn) {
                    str.append(' ');
                    currentColumn++;
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
