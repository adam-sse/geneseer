package net.ssehub.program_repair.geneseer.orchestrator;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;

public class Orchestrator {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Orchestrator.class.getName());
    
    private Defects4jWrapper defects4j = new Defects4jWrapper();
    
    private String runOnSingleBug(Bug bug) throws IOException, IllegalArgumentException {
        LOG.info(() -> "[" + bug + "] Preparing project...");
        Project config = defects4j.prepareProject(bug);
        
        LOG.info(() -> "[" + bug + "] Running geneseer...");
        List<String> command = new LinkedList<>();
        command.add("/usr/lib/jvm/java-1.17.0-openjdk-amd64/bin/java");
        command.add("-Djava.util.logging.config.class=net.ssehub.program_repair.geneseer.logging.LoggingConfiguration");
        command.add("-Djava.util.logging.config.file=logging.properties");
        command.add("-Dfile.encoding=" + bug.getEncoding().name());
        command.add("-cp");
        command.add("geneseer.jar");
        command.add("net.ssehub.program_repair.geneseer.Geneseer");
        command.add(bug.getDirectory().toString());
        command.add(config.getSourceDirectory().toString());
        command.add(config.getCompilationClasspath().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        command.add(config.getTestExecutionClassPath().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        command.addAll(config.getTestClassNames());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(bug.getDirectory().resolve("geneseer.log").toFile());
        pb.redirectOutput(Redirect.PIPE);
        Process process = pb.start();
        
        String stdout = new String(process.getInputStream().readAllBytes());
        if (stdout.endsWith("\n")) {
            stdout = stdout.substring(0, stdout.length() - 1);
        }
        
        if (ProcessRunner.untilNoInterruptedException(() -> process.waitFor()) != 0) {
            throw new IOException("Failed to run geneseer on " + bug);
        }
        
        LOG.info(() ->  "[" + bug + "] Completed");
        
        return bug.project() + ";" + bug.bug() + ";" + stdout;
    }
    
    public void runWithThreads(int numThreads) throws IOException {
        
        ConcurrentLinkedQueue<Bug> bugs = new ConcurrentLinkedQueue<>(defects4j.getBugs());
        
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", Locale.ROOT);
        Path outputPath = Path.of("output_" + formatter.format(now) + ".csv");
        LOG.info(() -> "Writing output to " + outputPath);
        
        try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            LOG.info(() -> "Starting " + numThreads + " threads...");
            List<Thread> threads = new LinkedList<>();
            for (int i = 0; i < numThreads; i++) {
                Thread th = new Thread(() -> {
                    Bug bug;
                    while ((bug = bugs.poll()) != null) {
                        try {
                            String output = runOnSingleBug(bug);
                            synchronized (writer) {
                                writer.write(output + "\n");
                                writer.flush();
                            }
                        } catch (IOException | IllegalArgumentException e) {
                            LOG.log(Level.SEVERE, "[" + bug + "] Failed to run", e);
                        }
                    }
                });
                th.start();
                threads.add(th);
            }
            
            threads.forEach(t -> ProcessRunner.untilNoInterruptedException(() -> {t.join(); return 0;}));
            LOG.info("All threads done");
        }
    }
    
    public static void main(String[] args) throws IllegalArgumentException, IOException {
        new Orchestrator().runWithThreads(Integer.parseInt(args[0]));
    }
    
}
