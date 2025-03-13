package net.ssehub.program_repair.geneseer;

public class VersionInfo {
    
    public static final String VERSION = "${project.version}";
    
    public static final String GIT_COMMIT = "${buildNumber}";
    
    public static final boolean GIT_DIRTY = "${buildIsTainted}".equals("tainted");

}
