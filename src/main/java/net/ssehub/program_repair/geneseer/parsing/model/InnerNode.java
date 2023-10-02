package net.ssehub.program_repair.geneseer.parsing.model;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public final class InnerNode extends Node {

    private final List<Node> children;
    
    public InnerNode(Type type) {
        super(type);
        this.children = new LinkedList<>();
    }

    @Override
    public String getText() {
        return children.stream().map(Node::getText).collect(Collectors.joining(" "));
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

}
