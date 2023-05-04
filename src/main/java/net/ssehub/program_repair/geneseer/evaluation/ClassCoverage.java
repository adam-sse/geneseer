package net.ssehub.program_repair.geneseer.evaluation;

import java.util.Set;

public record ClassCoverage(String className, Set<Integer> coveredLines, Set<Integer> partiallyCoveredLines) {

    public boolean isPartiallyOrFullyCovered(int lineNumber) {
        return coveredLines.contains(lineNumber) || partiallyCoveredLines.contains(lineNumber);
    }
    
}
