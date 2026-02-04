package net.ssehub.program_repair.geneseer.code;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public abstract class Node implements Cloneable {

    public static final Comparator<Node> DESCENDING_SUSPICIOUSNESS = (n1, n2) ->
            Double.compare((double) n2.getMetadata(Metadata.SUSPICIOUSNESS),
                    (double) n1.getMetadata(Metadata.SUSPICIOUSNESS));
    
    public enum Type {
        COMPILATION_UNIT,
        TYPE,
        METHOD,
        CONSTRUCTOR,
        ATTRIBUTE,
        STATEMENT,
        COMPOSIT_STATEMENT,
        LEAF,
        OTHER,
    }
    
    public enum Metadata {
        FILE_NAME,
        TYPE_NAME,
        METHOD_NAME,
        SUSPICIOUSNESS,
        COVERED_BY
    }
    
    protected boolean locked;
    
    private Type type;
    
    private Map<Metadata, Object> metadata;
    
    private String textCacheSingleLine;
    
    private String textCacheFormatted;
    
    public Node(Type type) {
        setType(type);
    }
    
    public final Type getType() {
        return type;
    }
    
    final void setType(Type type) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        if (type == Type.LEAF && !(this instanceof LeafNode)) {
            throw new IllegalArgumentException("Only LeafNodes may have type LEAF");
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
    
    public final void copyMetadataFromNode(Node other) {
        if (other.metadata != null) {
            this.metadata = new HashMap<>(other.metadata);
        }
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
    
    private final String getTextFormattedImpl() {
        String text = Writer.toText(this, n -> true);
        
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
    
    public final String getTextFormatted() {
        String result;
        if (locked) {
            if (textCacheFormatted == null) {
                textCacheFormatted = getTextFormattedImpl();
            }
            result = textCacheFormatted;
        } else {
            result = getTextFormattedImpl();
        }
        return result;
    }
    
    private final String getTextSingleLineImpl() {
        return Writer.toText(this, n -> true).trim().replaceAll("\\s+", " ");
    }
    
    public final String getTextSingleLine() {
        String result;
        if (locked) {
            if (textCacheSingleLine == null) {
                textCacheSingleLine = getTextSingleLineImpl();
            }
            result = textCacheSingleLine;
        } else {
            result = getTextSingleLineImpl();
        }
        return result;
    }
    
    @Override
    public final String toString() {
        return getTextFormatted();
    }
    
    public final String dumpTree() {
        StringBuilder sb = new StringBuilder();
        dumpTree(sb, "");
        return sb.toString();
    }
    
    protected abstract void dumpTree(StringBuilder target, String indentation);
    
    protected abstract List<Node> children();
    
    public final Stream<Node> stream() {
        return Stream.concat(Stream.of(this), children().stream().flatMap(Node::stream));
    }
    
    public final Node cheapClone(Node modifiableAt) {
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
    
    public final Optional<Node> findParent(Node child) {
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
    
    public final List<Node> getPath(Node child) {
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
    
    public final Node findEquivalentPath(Node otherRoot, Node toFind) throws IllegalArgumentException {
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

    public final Iterable<Node> childIterator() {
        return children();
    }
    
    public final int childCount() {
        return children().size();
    }
    
    public final boolean remove(Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        return children().remove(child);
    }
    
    public final void add(int index, Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().add(index, child);
    }
    
    public final void add(Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().add(child);
    }
    
    public final void set(int index, Node child) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().set(index, child);
    }
    
    public final void remove(int index) {
        if (locked) {
            throw new IllegalStateException("Node is locked");
        }
        children().remove(index);
    }
    
    public final int indexOf(Node child) {
        return children().indexOf(child);
    }
    
    public final Node get(int index) {
        return children().get(index);
    }
    
    public abstract boolean contentEquals(Node other);
    
}
