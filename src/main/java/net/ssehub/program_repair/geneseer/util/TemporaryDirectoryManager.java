package net.ssehub.program_repair.geneseer.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class TemporaryDirectoryManager implements Closeable {

    private static List<TemporaryDirectoryManager> openInstances = new LinkedList<>();

    private static final Logger LOG = Logger.getLogger(TemporaryDirectoryManager.class.getName());
    
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TemporaryDirectoryManager::closeAllOpenInstances));
        LOG.fine("Registered shutdown hook for TemporaryDirectoryManager");
    }
    
    private Set<Path> temporaryDirectories = new HashSet<>();
    
    private String name;
    
    private volatile boolean closed = false;
    
    public TemporaryDirectoryManager() {
        this("geneseer");
    }
    
    public TemporaryDirectoryManager(String name) {
        this.name = name;
        synchronized (openInstances) {
            openInstances.add(this);
        }
    }
    
    public synchronized Path createTemporaryDirectory() throws IOException, IllegalStateException {
        if (closed) {
            throw new IllegalStateException("already closed");
        } 
        Path directory = Files.createTempDirectory(name);
        temporaryDirectories.add(directory);
        return directory;
    }
    
    public synchronized void deleteTemporaryDirectory(Path temporaryDirectory)
            throws IOException, IllegalArgumentException {
        if (!closed) {
            if (!temporaryDirectories.remove(temporaryDirectory)) {
                throw new IllegalArgumentException(temporaryDirectory + " is not a directory created by this manager");
            }
            
            FileUtils.deleteDirectory(temporaryDirectory);
        }
    }
    
    @Override
    public synchronized void close() throws IOException {
        List<IOException> exceptions = new LinkedList<>();
        
        for (Path path : temporaryDirectories) {
            try {
                FileUtils.deleteDirectory(path);
            } catch (IOException e) {
                exceptions.add(e);
            }
        }
        temporaryDirectories.clear();
        closed = true;
        
        synchronized (openInstances) {
            openInstances.remove(this);
        }
        
        if (!exceptions.isEmpty()) {
            if (exceptions.size() == 1) {
                throw exceptions.get(0);
            } else {
                IOException combined = new IOException("Failed to delete temporary directories");
                for (IOException exc : exceptions) {
                    combined.addSuppressed(exc);
                }
                throw combined;
            }
        }
    }
    
    private static void logInShutdownHook(String message) {
        System.err.println("[TemporaryDirectoryManager ShutdownHook] " + message);
    }
    
    private static void closeAllOpenInstances() {
        synchronized (openInstances) {
            logInShutdownHook("Closing " + openInstances.size() + " open instances");
            while (!openInstances.isEmpty()) {
                try {
                    openInstances.get(0).close();
                } catch (IOException e) {
                    logInShutdownHook("Exception while closing instance: " + e.getMessage());
                }
            }
        }
    }

}
