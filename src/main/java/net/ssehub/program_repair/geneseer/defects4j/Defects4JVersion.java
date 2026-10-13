package net.ssehub.program_repair.geneseer.defects4j;

public enum Defects4JVersion {

    V_2_0_0(2, 0, 0),
    V_2_0_1(2, 0, 1),
    V_2_1_0(2, 1, 0),
    V_3_0_0(3, 0, 0),
    V_3_0_1(3, 0, 1);
    
    private int major;
    
    private int minor;
    
    private int patch;
    
    private Defects4JVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }
    
    public int major() {
        return major;
    }
    
    public int minor() {
        return minor;
    }
    
    public int patch() {
        return patch;
    }
    
}
