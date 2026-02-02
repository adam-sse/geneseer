package net.ssehub.program_repair.geneseer.util;

import java.util.List;

import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;

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
    
    public static String getMethodSignature(Node methodNode) {
        methodNode = methodNode.cheapClone(methodNode);
        List<Node> blocks = methodNode.stream().filter(n -> n.getType() == Type.COMPOSIT_STATEMENT).toList();
        for (Node block : blocks) {
            methodNode.remove(block);
        }
        return methodNode.getText();
    }
    
    public static String getFormattedText(Node node) {
        String text = Writer.toText(node);
        
        int lastPrefixNewline = -1;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                break;
            }
            if (text.charAt(i) == '\n') {
                lastPrefixNewline = i;
            }
        }
        if (lastPrefixNewline != -1) {
            text = text.substring(lastPrefixNewline + 1);
        }
        
        int firstSuffixWhiespace = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                break;
            }
            firstSuffixWhiespace = i;
        }
        if (firstSuffixWhiespace != -1) {
            text = text.substring(0, firstSuffixWhiespace);
        }
        
        return text;
    }
    
}
