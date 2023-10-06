package net.ssehub.program_repair.geneseer.parsing.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public final class LeafNode extends Node {

    private final String text;
    
    private Position originalPosition;
    
    public LeafNode(String text, Position originalPosition) {
        super(Type.LEAF);
        this.text = text;
        this.originalPosition = originalPosition;
    }
    
    public Position getOriginalPosition() {
        return originalPosition;
    }
    
    public void clearOriginalPosition() {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        this.originalPosition = null;
    }
    
    public void setOriginalPosition(Position originalPosition) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        this.originalPosition = originalPosition;
    }

    @Override
    public String getTextImpl() {
        return text;
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
    public Node clone() {
        Node clone = new LeafNode(text, originalPosition);
        clone.setMetadata(new HashMap<>(getMetadata() != null ? getMetadata() : Collections.emptyMap()));
        return clone;
    }

    @Override
    protected Node cloneWithGivenChildren(List<Node> clonedChildren) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean hasLine(int lineNumber) {
        return originalPosition != null ? originalPosition.line() == lineNumber : false;
    }

}
