package net.ssehub.program_repair.geneseer.parsing.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public abstract class Node {

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
    
}
