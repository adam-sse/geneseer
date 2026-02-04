package net.ssehub.program_repair.geneseer.code;

import java.util.Collections;
import java.util.List;

public final class LeafNode extends Node {

    private final String text;
    
    private int prefixNewlines;
    
    private int prefixSpaces;
    
    public LeafNode(String text) {
        super(Type.LEAF);
        this.text = text;
    }
    
    public String getText() {
        return text;
    }
    
    public int getPrefixNewlines() {
        return prefixNewlines;
    }
    
    public void setPrefixNewlines(int prefixNewlines) {
        this.prefixNewlines = prefixNewlines;
    }
    
    public int getPrefixSpaces() {
        return prefixSpaces;
    }

    public void setPrefixSpaces(int prefixSpaces) {
        this.prefixSpaces = prefixSpaces;
    }
    
    @Override
    public List<Node> children() {
        return Collections.emptyList();
    }
    
    @Override
    protected void dumpTree(StringBuilder target, String indentation) {
        target.append(indentation).append(text).append('\n');
    }

    @Override
    public LeafNode clone() {
        LeafNode clone = new LeafNode(text);
        clone.copyMetadataFromNode(this);
        clone.prefixNewlines = this.prefixNewlines;
        clone.prefixSpaces = this.prefixSpaces;
        return clone;
    }

    @Override
    protected Node cloneWithGivenChildren(List<Node> clonedChildren) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean contentEquals(Node other) {
        boolean result = false;
        if (other instanceof LeafNode otherLeaf) {
            result = this.text.equals(otherLeaf.text);
        }
        return result;
    }
    
}
