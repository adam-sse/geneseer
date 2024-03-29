package net.ssehub.program_repair.geneseer.log_analysis;

import java.util.List;
import java.util.function.Predicate;

interface AnalysisEntity {

    public void gather(List<LogLine> logLines);
    
    public List<String> getCsvHeader();
    
    public List<String> getCsvContent();
    
    static int findIndexByContent(List<LogLine> logLines, Predicate<String> contentPredicate) {
        int result = -1;
        for (int i = 0; i < logLines.size(); i++) {
            if (contentPredicate.test(logLines.get(i).content())) {
                result = i;
                break;
            }
        }
        return result;
    }
    
}
