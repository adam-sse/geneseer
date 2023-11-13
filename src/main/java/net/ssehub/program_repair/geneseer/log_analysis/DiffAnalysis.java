package net.ssehub.program_repair.geneseer.log_analysis;

import java.util.List;

public class DiffAnalysis implements AnalysisEntity {

    private String result;
    
    @Override
    public void gather(List<LogLine> logLines) {
        for (LogLine line : logLines) {
            if (line.content().startsWith("Best variant ")) {
                result = analyzePatch(line.content());
            }
        }
        if (result == null) {
            result = "NO_DIFF_FOUND";
        }
    }

    private String analyzePatch(String logLine) {
        String result;
        if (logLine.startsWith("Best variant V_000000{") || logLine.indexOf('\n') == -1) {
            result = "NO_MODIFICATION";
        } else {
            String diff = logLine.substring(logLine.indexOf('\n') + 1);
            
            String[] difflines = diff.split("\n");
            int i;
            for (i = 0; i < difflines.length; i++) {
                if (difflines[i].startsWith("@@")) {
                    break;
                }
            }
            
            int countAdd = 0;
            int countRemove = 0;
            for (; i < difflines.length; i++) {
                String l = difflines[i];
                if (l.startsWith("+") && !l.substring(1).isBlank()) {
                    countAdd++;
                } else if (l.startsWith("+") && !l.substring(1).isBlank()) {
                    countRemove++;
                } else if (l.startsWith("diff --git ")) {
                    for (; i < difflines.length; i++) {
                        if (difflines[i].startsWith("@@")) {
                            break;
                        }
                    }
                }
            }
            
            if (countAdd == 0) {
                if (countRemove == 0) {
                    result = "ONLY_DELETE";
                } else {
                    result = "NO_MODIFICATION";
                }
            } else {
                result = "ADDED";
            }
        }
        
        return result;
    }
    
    @Override
    public List<String> getCsvHeader() {
        return List.of("Diff Analysis");
    }

    @Override
    public List<String> getCsvContent() {
        return List.of(result);
    }

}
