package net.ssehub.program_repair.geneseer.defects4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.CliArguments;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class FileSizeCounter {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(FileSizeCounter.class.getName());
 
    private Defects4jWrapper defects4j;

    private List<Bug> bugs;
    
    public FileSizeCounter(Path defects4jPath) {
        this.defects4j = new Defects4jWrapper(defects4jPath);
    }
    
    public void selectAllBugs() throws IOException {
        bugs = defects4j.getAllBugs();
        LOG.info(() -> "Running on all " + bugs.size() + " bugs");
    }
    
    public List<Bug> getBugsOfProject(String project) throws IllegalArgumentException, IOException {
        return defects4j.getBugsOfProject(project);
    }
    
    public void selectBugs(List<Bug> bugs) {
        this.bugs = bugs;
        LOG.info(() -> "Running on " + bugs.size() + " specified bugs");
    }
    
    public record File(String path, int lines, int tokens) {
    }
    
    public void run() throws IOException {
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            for (Bug bug : bugs) {
                LOG.info(() -> "Running on " + bug);
                try {
                    Path baseDir = bug.getDirectory();
                    Path srcDir = baseDir.resolve(defects4j.getRelativeSourceDirectory(baseDir));
                
                    List<File> files = Files.walk(srcDir)
                            .filter(Files::isRegularFile)
                            .filter(f -> f.getFileName().toString().endsWith(".java"))
                            .map(f -> createFile(bug, srcDir, f))
                            .toList();
                    
                    if (files.isEmpty()) {
                        LOG.warning(() -> "Found no Java files in " + srcDir);
                    }
                    
                    Path jsonFile = bug.getDirectory().resolve("geneseer-file-overview.json");
                    LOG.info(() -> "Writing JSON to " + jsonFile);
                    Gson gson = new Gson();
                    Files.writeString(jsonFile, gson.toJson(files), StandardCharsets.UTF_8);
                    
                } catch (UncheckedIOException e) {
                    LOG.log(Level.WARNING, "Failed to run on bug " + bug, e.getCause());
                    throw e.getCause();
                    
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to run on bug " + bug, e);
                }
            }
        }
    }
    
    private static File createFile(Bug bug, Path sourceDirectory, Path absoluteFilePath) throws UncheckedIOException {
        Charset encoding = bug.project().equals("Lang") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;

        int numLines;
        int numTokens;
        try {
            List<String> lines = Files.readAllLines(absoluteFilePath, encoding);
            numLines = lines.size();
            String fullFile = lines.stream().collect(Collectors.joining("\n"));
            Encoding tokenEncoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
            numTokens = tokenEncoding.countTokensOrdinary(fullFile);
            
        } catch (MalformedInputException e) {
            throw new UncheckedIOException(new IOException("Encoding error in file " + absoluteFilePath, e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        
        return new File(sourceDirectory.relativize(absoluteFilePath).toString(), numLines, numTokens);
    }
    
    public static void main(String[] args) throws IOException {
        CliArguments cli = new CliArguments(args, Set.of("--defects4j"));
        
        FileSizeCounter patchWriter = new FileSizeCounter(Path.of(cli.getOptionOrThrow("--defects4j")));
        
        if (cli.getRemaining().isEmpty()) {
            patchWriter.selectAllBugs();
            
        } else {
            List<Bug> bugs = new LinkedList<>();
            for (String arg : cli.getRemaining()) {
                String[] parts = arg.split("/");
                if (parts[1].equals("*")) {
                    bugs.addAll(patchWriter.getBugsOfProject(parts[0]));
                } else {
                    bugs.add(new Bug(parts[0], Integer.parseInt(parts[1])));
                }
            }
            patchWriter.selectBugs(bugs);
        }
        
        patchWriter.run();
    }
    
}
