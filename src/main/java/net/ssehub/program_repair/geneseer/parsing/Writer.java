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
    
    public static String toText(Node root) {
        Stack<Node> nodes = new Stack<>();
        nodes.push(root);
        
        StringBuilder str = new StringBuilder();
        
        while (!nodes.isEmpty()) {
            Node currentNode = nodes.pop();
            
            if (currentNode instanceof LeafNode leaf) {
                for (int i = 0; i < leaf.getPrefixNewlines(); i++) {
                    str.append('\n');
                }
                for (int i = 0; i < leaf.getPrefixSpaces(); i++) {
                    str.append(' ');
                }
                
                String text = leaf.getText();
                str.append(text);
                
            } else {
                for (int i = currentNode.childCount() - 1; i >= 0; i--) {
                    nodes.push(currentNode.get(i));
                }
            }
        }
        
        str.append('\n');
        return str.toString();
    }
    
}
