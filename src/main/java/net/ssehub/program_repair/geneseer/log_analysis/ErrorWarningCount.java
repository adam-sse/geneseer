package net.ssehub.program_repair.geneseer.log_analysis;

import java.util.List;
import java.util.logging.Level;

class ErrorWarningCount implements AnalysisEntity {

    private long numErrorsAndWarnings;
    
    @Override
    public void gather(List<LogLine> logLines) {
        for (LogLine line : logLines) {
            if (line.level().intValue() >= Level.WARNING.intValue()) {
                numErrorsAndWarnings++;
            }
        }
    }

    @Override
    public List<String> getCsvHeader() {
        return List.of("Errors/Warnings");
    }

    @Override
    public List<String> getCsvContent() {
        return List.of(Long.toString(numErrorsAndWarnings));
    }

}
