package net.ssehub.program_repair.geneseer.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class TemporaryDirectoryManager implements Closeable {

    private Set<Path> temporaryDirectories = new HashSet<>();
    
    public Path createTemporaryDirectory() throws IOException {
        Path directory = Files.createTempDirectory("geneseer");
        temporaryDirectories.add(directory);
        return directory;
    }
    
    public void deleteTemporaryDirectory(Path temporaryDirectory) throws IOException, IllegalArgumentException {
        if (!temporaryDirectories.remove(temporaryDirectory)) {
            throw new IllegalArgumentException(temporaryDirectory + " is not a directory created by this manager");
        }
        deleteDirectory(temporaryDirectory);
    }
    
    @Override
    public void close() throws IOException {
        List<IOException> exceptions = new LinkedList<>();
        
        for (Path path : temporaryDirectories) {
            try {
                deleteDirectory(path);
            } catch (IOException e) {
                exceptions.add(e);
            }
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
    
    private static void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(file -> {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

}
