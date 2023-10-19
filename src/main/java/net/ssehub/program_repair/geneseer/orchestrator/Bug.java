package net.ssehub.program_repair.geneseer.orchestrator;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public record Bug(String project, int bug) {

    public Path getDirectory() {
        return Path.of(project, Integer.toString(bug));
    }
    
    public Charset getEncoding() {
        Charset result;
        if (project.equals("Lang")) {
            result = StandardCharsets.ISO_8859_1;
        } else {
            result = StandardCharsets.UTF_8;
        }
        return result;
    }
    
    @Override
    public String toString() {
        return project + "/" + bug;
    }
    
}
