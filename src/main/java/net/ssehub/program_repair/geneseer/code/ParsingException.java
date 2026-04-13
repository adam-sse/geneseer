package net.ssehub.program_repair.geneseer.code;

import java.nio.file.Path;

public class ParsingException extends Exception {

    private static final long serialVersionUID = 4940920118829042624L;

    private Path file;
    
    private int line;
    
    private int charPositionInLine;
    
    private String parserMessage;
    
    public ParsingException(Path file, int line, int charPositionInLine, String parserMessage) {
        this.file = file;
        this.line = line;
        this.charPositionInLine = charPositionInLine;
        this.parserMessage = parserMessage;
    }
    
    public Path getFile() {
        return file;
    }
    
    public int getLine() {
        return line;
    }
    
    public int getCharPositionInLine() {
        return charPositionInLine;
    }
    
    public String getParserMessage() {
        return parserMessage;
    }
    
    @Override
    public String getMessage() {
        return file + ":" + line + ":" + charPositionInLine + " " + parserMessage;
    }
    
}
