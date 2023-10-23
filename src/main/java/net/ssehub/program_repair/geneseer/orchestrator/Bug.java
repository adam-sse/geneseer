package net.ssehub.program_repair.geneseer.orchestrator;

import java.nio.file.Path;

public record Bug(String project, int bug) {

    public Path getDirectory() {
        return Path.of(project, Integer.toString(bug));
    }
    
    @Override
    public String toString() {
        return project + "/" + bug;
    }
    
}
