package net.ssehub.program_repair.geneseer.log_analysis;

import java.util.List;

class AstNodeCount implements AnalysisEntity {

    private long astNodeCount;
    
    @Override
    public void gather(List<LogLine> logLines) {
        int index = AnalysisEntity.findIndexByContent(logLines, line -> line.endsWith(" nodes in AST"));
        if (index != -1) {
            LogLine line = logLines.get(index);
            
            String countString = line.content().substring(0, line.content().length() - " nodes in AST".length()); 
            
            try {
                astNodeCount = Long.parseLong(countString);
            } catch (IllegalArgumentException e) {
                astNodeCount = -1;
            }
        } else {
            astNodeCount = -1;
        }
    }

    @Override
    public List<String> getCsvHeader() {
        return List.of("Nodes in AST");
    }

    @Override
    public List<String> getCsvContent() {
        if (astNodeCount >= 0) {
            return List.of(Long.toString(astNodeCount));
        } else {
            return List.of("");
        }
    }

}
