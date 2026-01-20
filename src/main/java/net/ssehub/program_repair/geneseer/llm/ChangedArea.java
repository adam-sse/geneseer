package net.ssehub.program_repair.geneseer.llm;

public record ChangedArea(String file, int start, int size) {
    
    public int end() {
        return start + Math.max(size - 1, 0);
    }
    
}
