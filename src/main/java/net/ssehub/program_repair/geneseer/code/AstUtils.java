package net.ssehub.program_repair.geneseer.code;

import java.util.List;
import java.util.function.Predicate;

import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;

public class AstUtils {

    private AstUtils() {
    }
    
    public static LeafNode getFirstLeafNode(Node node) {
        while (node.getType() != Type.LEAF) {
            node = node.get(0);
        }
        return (LeafNode) node;
    }
    
    public static int getLine(Node fileOrRootNode, Node childNode) {
        if (fileOrRootNode.getMetadata(Metadata.FILE_NAME) == null) {
            List<Node> path = fileOrRootNode.getPath(childNode);
            if (path.size() > 1 && path.get(1).getMetadata(Metadata.FILE_NAME) != null) {
                fileOrRootNode = path.get(1);
            } else {
                throw new IllegalArgumentException("Can only call getLine() with first parameter being the file");
            }
        }
        
        int previousLines = 1 + fileOrRootNode.stream()
                .takeWhile(n -> n != childNode)
                .filter(n -> n.getType() == Type.LEAF)
                .mapToInt(n -> ((LeafNode) n).getPrefixNewlines())
                .sum();
        
        return previousLines + getFirstLeafNode(childNode).getPrefixNewlines();
    }
    
    public static int getAdditionalLineCount(Node node) {
        return node.stream()
                .filter(n -> n.getType() == Type.LEAF)
                .skip(1)
                .mapToInt(n -> ((LeafNode) n).getPrefixNewlines())
                .sum();
    }
    
    public static String getSignature(Node typeOrMethodNode) {
        Predicate<Node> filter;
        if (typeOrMethodNode.getType() == Type.METHOD || typeOrMethodNode.getType() == Type.CONSTRUCTOR) {
            filter = n -> n.getType() != Type.COMPOSIT_STATEMENT;
        } else {
            filter = Predicate.not(
                    n -> n.childCount() > 0
                    && n.get(0) instanceof LeafNode leaf && leaf.getText().equals("{"));
        }
        return Writer.toText(typeOrMethodNode, filter).trim().replaceAll("\\s+", " ");
    }
    
}
