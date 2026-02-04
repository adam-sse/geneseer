package net.ssehub.program_repair.geneseer.code;

import java.util.LinkedList;
import java.util.List;

public final class InnerNode extends Node {

    private final List<Node> children;
    
    public InnerNode(Type type) {
        super(type);
        this.children = new LinkedList<>();
    }

    @Override
    public List<Node> children() {
        return children;
    }

    @Override
    protected void dumpTree(StringBuilder target, String indentation) {
        target.append(indentation).append(getType()).append('\n');
        indentation += '\t';
        for (Node child : children) {
            child.dumpTree(target, indentation);
        }
    }

    @Override
    public InnerNode clone() {
        InnerNode clone = new InnerNode(getType());
        for (Node child : children) {
            clone.children.add(child.clone());
        }
        clone.copyMetadataFromNode(this);
        return clone;
    }

    @Override
    protected Node cloneWithGivenChildren(List<Node> clonedChildren) {
        InnerNode clone = new InnerNode(getType());
        clone.children.addAll(clonedChildren);
        clone.copyMetadataFromNode(this);
        return clone;
    }
    
    @Override
    public boolean contentEquals(Node other) {
        boolean result = false;
        if (other instanceof InnerNode otherInner && this.children.size() == otherInner.children.size()) {
            result = true;
            for (int i = 0; i < this.children.size(); i++) {
                if (!this.children.get(i).contentEquals(otherInner.children.get(i))) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }
    
}
