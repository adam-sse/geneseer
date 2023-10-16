package net.ssehub.program_repair.geneseer.log_analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class TimingCategories implements AnalysisEntity {

    private static final String[] TIMING_CATEGORIES = {
            "genetic-algorithm",
            "parsing",
            "code-writing",
            "compilation",
            "junit-evaluation",
            "flacoco",
            "junit-coverage-matrix"};
    
    private static final Pattern TIMING_MEASUREMENT = Pattern.compile("^    (?<name>.+): (?<milliseconds>[0-9]+) ms$");
    
    private Map<String, Long> measurements;
    
    @Override
    public void gather(List<LogLine> logLines) {
        measurements = new HashMap<>(TIMING_CATEGORIES.length);
        
        int timinMeasurementIndex = AnalysisEntity.findIndexByContent(logLines,
                line -> line.equals("Timing measurements:"));
        if (timinMeasurementIndex != -1) {
            
            for (int i = timinMeasurementIndex + 1; i < logLines.size(); i++) {
                LogLine measurementLine = logLines.get(i);
                Matcher matcher = TIMING_MEASUREMENT.matcher(measurementLine.content());
                if (matcher.matches()) {
                    measurements.put(matcher.group("name"), Long.parseLong(matcher.group("milliseconds")));
                } else {
                    break;
                }
            }
        }
    }

    @Override
    public List<String> getCsvHeader() {
        List<String> csv = new ArrayList<>(TIMING_CATEGORIES.length + 1);
        for (String name : TIMING_CATEGORIES) {
            csv.add(name + " [ms]");
        }
        csv.add("Other Categories");
        return csv;
    }

    @Override
    public List<String> getCsvContent() {
        List<String> csv = new ArrayList<>(TIMING_CATEGORIES.length + 1);
        
        for (String name : TIMING_CATEGORIES) {
            if (measurements.containsKey(name)) {
                csv.add(Long.toString(measurements.remove(name)));
            } else {
                csv.add("");
            }
        }
        
        StringBuilder others = new StringBuilder();
        if (!measurements.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, Long> measure : measurements.entrySet()) {
                if (!first) {
                    others.append(',');
                }
                first = false;
                others.append(measure.getKey())
                        .append('=')
                        .append(measure.getValue());
            }
        }
        csv.add(others.toString());
        
        return csv;
    }
    
}
