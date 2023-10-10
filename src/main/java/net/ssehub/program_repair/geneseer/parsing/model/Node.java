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
        SUSPICIOUSNESS,
    }
    
    private Type type;
    
    private Map<Metadata, Object> metadata;
    
    protected boolean locked;
    
    private String textCache;
    
    public Node(Type type) {
        this.type = type;
    }
    
    public Type getType() {
        return type;
    }
    
    public void setType(Type type) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
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
    
    protected Map<Metadata, Object> getMetadata() {
        return metadata;
    }
    
    protected void setMetadata(Map<Metadata, Object> metadata) {
        this.metadata = metadata;
    }
    
    public final void lock() {
        if (!locked) {
            locked = true;
            for (Node child : children()) {
                child.lock();
            }
        }
    }
    
    public final boolean isLocked() {
        return locked;
    }
    
    public final String getText() {
        if (locked) {
            if (textCache == null) {
                textCache = getTextImpl();
            }
            return textCache;
        }
        return getTextImpl();
    }
    
    protected abstract String getTextImpl();
    
    @Override
    public String toString() {
        return getText();
    }
    
    public final String dumpTree() {
        StringBuilder sb = new StringBuilder();
        dumpTree(sb, "");
        return sb.toString();
    }
    
    protected abstract void dumpTree(StringBuilder target, String indentation);
    
    protected abstract List<Node> children();
    
    public Stream<Node> stream() {
        return Stream.concat(Stream.of(this), children().stream().flatMap(Node::stream));
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
    public abstract Node clone();
    
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
    
    public abstract boolean hasLine(int lineNumber);
    
    public Node findEquivalentPath(Node otherRoot, Node toFind) throws IllegalArgumentException {
        if (otherRoot == toFind) {
            return this;
        }
        
        for (int i = 0; i < Math.min(children().size(), otherRoot.children().size()); i++) {
            Node result = children().get(i).findEquivalentPath(otherRoot.children().get(i), toFind);
            if (result != null) {
                return result;
            }
        }
        
        return null;
    }

    public Iterable<Node> childIterator() {
        return children();
    }
    
    public int childCount() {
        return children().size();
    }
    
    public boolean remove(Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        return children().remove(child);
    }
    
    public void add(int index, Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().add(index, child);
    }
    
    public void add(Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().add(child);
    }
    
    public void set(int index, Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().set(index, child);
    }
    
    public void remove(int index) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().remove(index);
    }
    
    public int indexOf(Node child) {
        return children().indexOf(child);
    }
    
    public Node get(int index) {
        return children().get(index);
    }
    
}
