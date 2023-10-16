package net.ssehub.program_repair.geneseer.log_analysis;

import java.time.temporal.ChronoUnit;
import java.util.List;

class TotalElapsedTime implements AnalysisEntity {

    private long elapsedMillis;
    
    @Override
    public void gather(List<LogLine> logLines) {
        LogLine first = logLines.get(0);
        LogLine last = logLines.get(logLines.size() - 1);
        elapsedMillis = ChronoUnit.MILLIS.between(first.timestamp(), last.timestamp());
    }

    @Override
    public List<String> getCsvHeader() {
        return List.of("Total Elapsed Time [ms]");
    }

    @Override
    public List<String> getCsvContent() {
        return List.of(Long.toString(elapsedMillis));
    }

}
