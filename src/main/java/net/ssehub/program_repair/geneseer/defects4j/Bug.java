package net.ssehub.program_repair.geneseer.defects4j;

import java.nio.file.Path;

record Bug(String project, int bug) {

    public Path getDirectory() {
        return Path.of(project, Integer.toString(bug));
    }
    
    @Override
    public String toString() {
        return project + "/" + bug;
    }
    
}
