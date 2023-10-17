package net.ssehub.program_repair.geneseer.log_analysis;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class LogAnalysis {
    
    private String filename;
    
    private List<AnalysisEntity> analysisEntities;
    
    public LogAnalysis(Path file) throws IOException, IllegalArgumentException {
        this.filename = file.toString();
        analyze(readFile(file));
    }
    
    private List<String> readFile(Path file) throws IOException {
        try {
            return Files.readAllLines(file);
        } catch (MalformedInputException e) {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        }
    }

    private void analyze(List<String> lines) throws IOException, IllegalArgumentException {
        List<LogLine> logLines = LogLine.parse(lines);
        if (logLines.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        
        analysisEntities = new LinkedList<>();
        
        analysisEntities.add(new AstNodeCount());
        analysisEntities.add(new SuspiciousStatementCount());
        analysisEntities.add(new TotalElapsedTime());
        analysisEntities.add(new TimingCategories());
        
        for (AnalysisEntity entity : analysisEntities) {
            entity.gather(logLines);
        }
    }
    
    public String getCsvHeader() {
        List<String> header = new LinkedList<>();
        header.add("File");
        for (AnalysisEntity entity : analysisEntities) {
            header.addAll(entity.getCsvHeader());
        }
        return header.stream().collect(Collectors.joining(";"));
    }

    public String toCsvLine() {
        List<String> csv = new LinkedList<>();
        csv.add(filename);
        for (AnalysisEntity entity : analysisEntities) {
            csv.addAll(entity.getCsvContent());
        }
        return csv.stream().collect(Collectors.joining(";"));
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("No input files specified");
        }

        boolean first = true;
        for (String path : args) {
            try {
                LogAnalysis analysis = new LogAnalysis(Path.of(path));
                if (first) {
                    System.out.println(analysis.getCsvHeader());
                    first = false;
                }
                System.out.println(analysis.toCsvLine());
                
            } catch (IllegalArgumentException e) {
                System.err.println(path + ": Failed to parse: " + e.getMessage());
            } catch (IOException e) {
                e.printStackTrace();
                System.err.println(path + ": Failed to read: " + e.getMessage());
            }
        }
    }
    
}
