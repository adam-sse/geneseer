package net.ssehub.program_repair.geneseer.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtils {

    private FileUtils() {}
    
    public static void copyAllNonJavaSourceFiles(Path fromDirectory, Path toDirectory) throws IOException {
        try {
            Files.walk(fromDirectory)
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".java"))
                    .forEach(path -> {
                        Path relative = fromDirectory.relativize(path);
                        Path target = toDirectory.resolve(relative);
                        try {
                            Files.createDirectories(target.getParent());
                            Files.copy(path, toDirectory.resolve(relative));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
    
}
