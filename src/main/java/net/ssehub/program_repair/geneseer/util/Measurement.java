package net.ssehub.program_repair.geneseer.util;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class Measurement {
    
    public static final Measurement INSTANCE = new Measurement();
    
    private static final Logger LOG = Logger.getLogger(Measurement.class.getName());
    
    private Map<String, Long> timings;
    
    public class Probe implements AutoCloseable {

        private String name;
        
        private long startMs;
        
        private Probe(String name) {
            this.name = name;
            this.startMs = System.currentTimeMillis();
        }
        
        @Override
        public void close() {
            long stopMs = System.currentTimeMillis();
            addTiming(name, stopMs - startMs);
        }
        
    }
    
    private Measurement() {
        this.timings = new HashMap<>();
    }
    
    private synchronized void addTiming(String name, long elapsedMs) {
        LOG.fine(() -> name + " took " + elapsedMs + " ms");
        timings.merge(name, elapsedMs, (oldSum, newElapsed) -> oldSum + newElapsed);
    }
    
    public Probe start(String name) {
        return new Probe(name);
    }
    
    public Long getAccumulatedTimings(String name) {
        return timings.get(name);
    }
    
    public Iterable<Map.Entry<String, Long>> finishedProbes() {
        return timings.entrySet();
    }

}
