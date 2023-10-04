package net.ssehub.program_repair.geneseer.genetic;

import java.util.Locale;

import spoon.reflect.path.CtPath;

public class SuspiciousStatement {

    private CtPath path;
    
    private double suspiciousness;
    
    public SuspiciousStatement(CtPath path, double suspiciousness) {
        this.path = path;
        this.suspiciousness = suspiciousness;
    }
    
    public double getSuspiciousness() {
        return suspiciousness;
    }
    
    public CtPath getPath() {
        return path;
    }
    
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%2.2f", suspiciousness) + " " + path;
    }
    
}
