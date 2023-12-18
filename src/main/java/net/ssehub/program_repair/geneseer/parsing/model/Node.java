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
    
    protected boolean locked;
    
    private Type type;
    
    private Map<Metadata, Object> metadata;
    
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
        String result;
        if (locked) {
            if (textCache == null) {
                textCache = getTextImpl();
            }
            result = textCache;
        } else {
            result = getTextImpl();
        }
        return result;
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
        Node clone;
        
        if (modifiableAt == this) {
            clone = clone();
            
        } else {
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
                clone = this;
            } else {
                clone = cloneWithGivenChildren(clonedChildren);
            }
        }
        
        return clone;
    }
    
    @Override
    public abstract Node clone();
    
    protected abstract Node cloneWithGivenChildren(List<Node> clonedChildren);
    
    public Optional<Node> findParent(Node child) {
        Optional<Node> result;
        
        if (children().contains(child)) {
            result = Optional.of(this);
            
        } else {
            result = Optional.empty();
            for (Node c : children()) {
                result = c.findParent(child);
                if (result.isPresent()) {
                    break;
                }
            }
        }
        
        return result;
    }
    
    public List<Node> getPath(Node child) {
        List<Node> result;
        
        if (children().contains(child)) {
            result = new LinkedList<>();
            result.add(0, child);
            result.add(0, this);
            
        } else {
            result = null;
            for (Node c : children()) {
                result = c.getPath(child);
                if (result != null) {
                    result.add(0, this);
                    break;
                }
            }
        }
        
        return result;
    }
    
    public abstract boolean hasLine(int lineNumber);
    
    public Node findEquivalentPath(Node otherRoot, Node toFind) throws IllegalArgumentException {
        Node result;
        
        if (otherRoot == toFind) {
            result = this;
            
        } else {
            result = null;
            for (int i = 0; i < Math.min(children().size(), otherRoot.children().size()); i++) {
                result = children().get(i).findEquivalentPath(otherRoot.children().get(i), toFind);
                if (result != null) {
                    break;
                }
            }
        }
        
        return result;
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
