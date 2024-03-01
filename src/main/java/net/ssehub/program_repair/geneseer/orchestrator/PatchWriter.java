package net.ssehub.program_repair.geneseer.orchestrator;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.Gson;

import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.orchestrator.Defects4jWrapper.Version;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.CliArguments;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class PatchWriter {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(PatchWriter.class.getName());
 
    private Defects4jWrapper defects4j;

    private List<Bug> bugs;
    
    public PatchWriter(Path defects4jPath) {
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
    
    public record ChangedArea(String file, int start, int size) {
    }
    
    public void run() throws IOException {
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager()) {
            for (Bug bug : bugs) {
                
                Path buggy = null;
                Path fixed = null;
                try {
                    
                    LOG.info(() -> "[" + bug + "] Checking out buggy version...");
                    buggy = tempDirManager.createTemporaryDirectory();
                    defects4j.checkout(bug, buggy, Version.BUGGY, false);
                    
                    LOG.info(() -> "[" + bug + "] Checking out fixed version...");
                    fixed = tempDirManager.createTemporaryDirectory();
                    defects4j.checkout(bug, fixed, Version.FIXED, false);
                    
                    Path buggySrc = buggy.resolve(defects4j.getRelativeSourceDirectory(buggy));
                    Path fixedSrc = fixed.resolve(defects4j.getRelativeSourceDirectory(fixed));
                    
                    Charset encoding = bug.project().equals("Lang")
                            ? StandardCharsets.ISO_8859_1 : Charset.defaultCharset();
                    String diff = AstDiff.getDiff(buggySrc, fixedSrc, encoding, 0);
                    
                    LOG.fine(() -> "Diff:\n" + diff);
                    
                    Path diffFile = bug.getDirectory().resolve("geneseer-human-patch.diff");
                    LOG.info(() -> "Writing diff to " + diffFile);
                    Files.writeString(diffFile, diff, encoding);
                    
                    List<ChangedArea> changedAreas = getChangedAreas(diff);
                    LOG.info(() -> changedAreas.stream().map(ChangedArea::toString).collect(Collectors.joining("\n")));
                    
                    Path jsonFile = bug.getDirectory().resolve("geneseer-changed-areas.json");
                    LOG.info(() -> "Writing JSON to " + jsonFile);
                    Gson gson = new Gson();
                    Files.writeString(jsonFile, gson.toJson(changedAreas), StandardCharsets.UTF_8);
                    
                    
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to run on bug " + bug, e);
                    
                } finally {
                    tryDelete(tempDirManager, buggy);
                    tryDelete(tempDirManager, fixed);
                }
            }
        }
    }

    private List<ChangedArea> getChangedAreas(String diff) {
        List<ChangedArea> changedAreas = new LinkedList<>();
        Path currentFile = null;
        Pattern hunkPattern = Pattern.compile("^@@ -(?<start>[0-9]+)(,(?<size>[0-9]+))? \\+[0-9]+(,[0-9]+)? @@");
        for (String line : diff.split("\n")) {
            if (line.startsWith("--- a/")) {
                currentFile = Path.of(line.substring("--- a/".length()));
                
            } else if (line.equals("--- /dev/null")) {
                currentFile = null;
                
            } else if (currentFile != null && line.startsWith("@@ ")) {
                Matcher m = hunkPattern.matcher(line);
                if (m.find()) {
                    String size = m.group("size");
                    if (size == null) {
                        size = "1";
                    }
                    
                    changedAreas.add(new ChangedArea(currentFile.toString(),
                            Integer.parseInt(m.group("start")), Integer.parseInt(size)));
                } else {
                    LOG.warning(() -> "Hunk header " + line + " does not match expected pattern "
                            + hunkPattern.pattern());
                }
            }
        }
        return changedAreas;
    }

    private void tryDelete(TemporaryDirectoryManager tempDirManager, Path temporaryDirectory) {
        if (temporaryDirectory != null) {
            try {
                tempDirManager.deleteTemporaryDirectory(temporaryDirectory);
            } catch (IOException e) {
                // ignore, will be cleaned up later
            }
        }
    }
    
    public static void main(String[] args) throws IOException {
        CliArguments cli = new CliArguments(args, Set.of("--defects4j"));
        
        PatchWriter patchWriter = new PatchWriter(Path.of(cli.getOptionOrThrow("--defects4j")));
        
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
