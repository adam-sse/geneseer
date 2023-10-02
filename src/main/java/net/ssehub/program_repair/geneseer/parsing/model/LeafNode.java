package net.ssehub.program_repair.geneseer.parsing.model;

import java.util.Collections;
import java.util.List;

public final class LeafNode extends Node {

    private final String text;
    
    private final Position originalPosition;
    
    public LeafNode(String text, Position originalPosition) {
        super(Type.LEAF);
        this.text = text;
        this.originalPosition = originalPosition;
    }
    
    public Position getOriginalPosition() {
        return originalPosition;
    }

    @Override
    public String getText() {
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
    protected Node clone() {
        return new LeafNode(text, originalPosition);
    }

    @Override
    protected Node cloneWithGivenChildren(List<Node> clonedChildren) {
        throw new UnsupportedOperationException();
    }

}
