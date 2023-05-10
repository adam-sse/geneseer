package eu.stamp_project.testrunner.listener.impl;

import eu.stamp_project.testrunner.listener.Coverage;

public class CoverageDetailed implements Coverage {
    
    private CoverageInformation detailedCoverage;
    
    public CoverageDetailed(CoverageInformation detailedCoverage) {
        this.detailedCoverage = detailedCoverage;
    }

    public CoverageInformation getDetailedCoverage() {
        return detailedCoverage;
    }
    
}
