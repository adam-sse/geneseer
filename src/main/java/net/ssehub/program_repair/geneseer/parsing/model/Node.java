package net.ssehub.program_repair.geneseer.parsing.model;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public abstract class Node implements Cloneable {

    public enum Type {
        COMPILATION_UNIT,
        SINGLE_STATEMENT,
        COMPOSIT_STATEMENT,
        LEAF,
        OTHER,
    }
    
    public enum Metadata {
        FILENAME,
    }
    
    private Type type;
    
    private Map<Metadata, Object> metadata;
    
    public Node(Type type) {
        this.type = type;
    }
    
    public Type getType() {
        return type;
    }
    
    public void setType(Type type) {
        this.type = type;
    }
    
    public final void setMetadata(Metadata key, Object value) {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
    }
    
    
    public final Object getMetadata(Metadata key) {
        return metadata != null ? metadata.get(key) : null;
    }
    
    public abstract String getText();
    
    @Override
    public String toString() {
        return dumpTree();
    }
    
    public final String dumpTree() {
        StringBuilder sb = new StringBuilder();
        dumpTree(sb, "");
        return sb.toString();
    }
    
    protected abstract void dumpTree(StringBuilder target, String indentation);
    
    public abstract List<Node> children();
    
    public Stream<Node> stream() {
        return Stream.of(this).mapMulti((node, consumer) -> {
            consumer.accept(node);
            for (Node child : node.children()) {
                child.stream().forEach(consumer::accept);
            }
        });
    }
    
    public Node cheapClone(Node modifiableAt) {
        if (modifiableAt == this) {
            return clone();
        }
        
        boolean anyChildDifferent = false;
        List<Node> clonedChildren = children().size() > 0 ? new LinkedList<>() : null;
        for (Node child : children()) {
            Node cloned = child.cheapClone(modifiableAt);
            clonedChildren.add(cloned);
            if (cloned != child) {
                anyChildDifferent = true;
            }
        }
        
        if (!anyChildDifferent) {
            return this;
        } else {
            return cloneWithGivenChildren(clonedChildren);
        }
    }
    
    @Override
    protected abstract Node clone();
    
    protected abstract Node cloneWithGivenChildren(List<Node> clonedChildren);
    
    public Optional<Node> findParent(Node child) {
        if (children().contains(child)) {
            return Optional.of(this);
        }
        
        for (Node c : children()) {
            Optional<Node> found = c.findParent(child);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
    
}
