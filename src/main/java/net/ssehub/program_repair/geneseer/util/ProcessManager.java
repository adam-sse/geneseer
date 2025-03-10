package net.ssehub.program_repair.geneseer.util;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ProcessManager {

    public static final ProcessManager INSTANCE = new ProcessManager();
    
    private static final Logger LOG = Logger.getLogger(ProcessManager.class.getName());

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::killAllRemainingProcesses));
        LOG.fine("Registered shutdown hook for ProcessManager");
    }
    
    private List<Process> processes = new LinkedList<>();
    
    private ProcessManager() {
    }
    
    public synchronized void trackProcess(Process process) {
        this.processes.add(process);
    }
    
    private static void logInShutdownHook(String message) {
        System.err.println("[ProcessManager ShutdownHook] " + message);
    }
    
    private synchronized void killAllRemainingProcesses() {
        logInShutdownHook("Cleaning up any remaining child processes");
        
        boolean anyAlive = false;
        for (Process process : processes) {
            if (process.isAlive()) {
                logInShutdownHook("Child process " + process.pid() + " is still alive; terminating...");
                process.destroy();
                anyAlive = true;
            }
        }
        if (anyAlive) {
            for (Process process : processes) {
                try {
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                }
                if (process.isAlive()) {
                    logInShutdownHook("Child process " + process.pid()
                            + " still alive after 100 ms; killing forcibly...");
                    process.destroyForcibly();
                }
            }
        } else {
            logInShutdownHook("No child processes still running");
        }
    }
    
}
