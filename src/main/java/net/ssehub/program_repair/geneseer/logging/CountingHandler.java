package net.ssehub.program_repair.geneseer.logging;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

class CountingHandler extends Handler {
    
    private Map<Level, AtomicInteger> counters = new HashMap<>();
    
    public CountingHandler() {
        counters.put(Level.SEVERE, new AtomicInteger());
        counters.put(Level.WARNING, new AtomicInteger());
        counters.put(Level.INFO, new AtomicInteger());
        counters.put(Level.CONFIG, new AtomicInteger());
        counters.put(Level.FINE, new AtomicInteger());
        counters.put(Level.FINER, new AtomicInteger());
        counters.put(Level.FINEST, new AtomicInteger());
    }

    public int getMessageCount(Level level) {
        return counters.get(level).get();
    }
    
    @Override
    public void close() {
    }

    @Override
    public void flush() {
    }

    @Override
    public void publish(LogRecord logRecord) {
        AtomicInteger counter = counters.get(logRecord.getLevel());
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

}
