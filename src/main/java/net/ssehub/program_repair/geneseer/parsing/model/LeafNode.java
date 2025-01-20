package net.ssehub.program_repair.geneseer.parsing.model;

import java.util.Collections;
import java.util.List;

public final class LeafNode extends Node {

    private final String text;
    
    private Position position;
    
    public LeafNode(String text, Position position) {
        super(Type.LEAF);
        this.text = text;
        this.position = position;
    }
    
    public Position getPosition() {
        return position;
    }
    
    public void setPosition(Position position) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        this.position = position;
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
        Node clone = new LeafNode(text, position);
        clone.copyMetadataFromNode(this);
        return clone;
    }

    @Override
    protected Node cloneWithGivenChildren(List<Node> clonedChildren) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public boolean hasLine(int lineNumber) {
        return position != null ? position.line() == lineNumber : false;
    }

}
