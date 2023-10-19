package net.ssehub.program_repair.geneseer.log_analysis;

import java.util.List;

class SuspiciousStatementCount implements AnalysisEntity {

    private int suspiciousStatementCount;
    
    @Override
    public void gather(List<LogLine> logLines) {
        int index = AnalysisEntity.findIndexByContent(logLines, line -> line.endsWith(" suspicious statements"));
        if (index != -1) {
            LogLine line = logLines.get(index);
            
            String countString = line.content()
                    .substring(0, line.content().length() - " suspicious statements".length()); 
            
            try {
                suspiciousStatementCount = Integer.parseInt(countString);
            } catch (IllegalArgumentException e) {
                suspiciousStatementCount = -1;
            }
        } else {
            suspiciousStatementCount = -1;
        }
    }

    @Override
    public List<String> getCsvHeader() {
        return List.of("Suspicious Statements");
    }

    @Override
    public List<String> getCsvContent() {
        List<String> result;
        if (suspiciousStatementCount >= 0) {
            result = List.of(Integer.toString(suspiciousStatementCount));
        } else {
            result = List.of("");
        }
        return result;
    }

}
