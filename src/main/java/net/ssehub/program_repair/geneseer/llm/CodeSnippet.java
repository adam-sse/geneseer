package net.ssehub.program_repair.geneseer.llm;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.Node;

public class CodeSnippet {
    
    private Path file;
    
    private LineRange lineRange;
    
    private List<String> lines;
    
    private List<String> newLines;
    
    public CodeSnippet(Path file, LineRange lineRange, List<String> lines) {
        this.file = file;
        this.lineRange = lineRange;
        this.lines = lines;
    }
    
    public static CodeSnippet getSnippetForMethod(Node root, Node method) {
        Path filename = AstUtils.getFile(root, method);
        LineRange range = LineRange.getRange(root, method);
        List<String> lines = Arrays.asList(method.getTextFormatted().split("\n"));
        
        return new CodeSnippet(filename, range, lines);
    }
    
    public record LineRange(int start, int end) {
        
        public int size() {
            return end - start + 1;
        }
        
        public static LineRange getRange(Node root, Node node) {
            int start = AstUtils.getLine(root, node);
            int end = start + AstUtils.getAdditionalLineCount(node);
            return new LineRange(start, end);
        }
    }
    
    public Path getFile() {
        return file;
    }
    
    public LineRange getLineRange() {
        return lineRange;
    }
    
    public int size() {
        return lineRange.size();
    }
    
    public String getText() {
        return lines.stream().collect(Collectors.joining("\n"));
    }
    
    List<String> getNewLines() {
        return newLines;
    }
    
    void setNewLines(List<String> newLines) {
        this.newLines = newLines;
    }

}