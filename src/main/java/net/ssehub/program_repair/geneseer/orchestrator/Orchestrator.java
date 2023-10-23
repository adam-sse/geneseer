package net.ssehub.program_repair.geneseer.orchestrator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Geneseer;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.SetupTest;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.CliArguments;
import net.ssehub.program_repair.geneseer.util.ProcessRunner;
import net.ssehub.program_repair.geneseer.util.TimeUtils;

public class Orchestrator {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Orchestrator.class.getName());
    
    public enum Target {
        GENESEER, SETUP_TEST
    }
    
    private String jvmExecutable;
    
    private String geneseerJar;
    
    private Target target = Target.GENESEER;
    
    private Defects4jWrapper defects4j;
    
    private List<Bug> bugs;
    
    private int numThreads = 1;
    
    private int bugsFinished;
    
    private long totalBugRuntimeInSeconds;
    
    private synchronized Bug nextBug() {
        Bug nextBug;
        if (!bugs.isEmpty()) {
            nextBug = bugs.remove(0);
            LOG.info(() -> "Pulled " + nextBug + " from queue");

            if (bugsFinished > 0) {
                long secondsPerBug = totalBugRuntimeInSeconds / bugsFinished;
                int remaining = bugs.size() + 1;
                long secondsRemaining = (secondsPerBug * remaining) / numThreads;
                
                LOG.info(() -> TimeUtils.formatSeconds(secondsPerBug) + " per bug times " + remaining
                        + " remaining in " + numThreads +  " threads = " + TimeUtils.formatSeconds(secondsRemaining));
            }
        } else {
            nextBug = null;
        }
        return nextBug;
    }
    
    private String runSetupTest(Bug bug) throws IOException, IllegalArgumentException {
        LOG.info(() -> "[" + bug + "] Preparing project...");
        Project config = defects4j.prepareProject(bug);
        
        LOG.info(() -> "[" + bug + "] Running geneseer setup test...");
        List<String> command = new LinkedList<>();
        command.add(jvmExecutable);
        command.add("-Djava.util.logging.config.class=" + LoggingConfiguration.class.getName());
        command.add("-Djava.util.logging.config.file=logging.properties");
        command.add("-Dfile.encoding=" + bug.getEncoding().name());
        command.add("-cp");
        command.add(geneseerJar);
        command.add(SetupTest.class.getName());
        command.add("--project-directory");
        command.add(bug.getDirectory().toString());
        command.add("--source-directory");
        command.add(config.getSourceDirectory().toString());
        if (!config.getCompilationClasspath().isEmpty()) {
            command.add("--compile-classpath");
            command.add(config.getCompilationClasspath().stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        command.add("--test-classpath");
        command.add(config.getTestExecutionClassPath().stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)));
        command.add("--test-classes");
        command.add(config.getTestClassNames().stream()
                .collect(Collectors.joining(":")));
    
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(bug.getDirectory().resolve("geneseer-setup-test.log").toFile());
        pb.redirectOutput(Redirect.PIPE);
        Process process = pb.start();
        
        String stdout = new String(process.getInputStream().readAllBytes());
        if (stdout.endsWith("\n")) {
            stdout = stdout.substring(0, stdout.length() - 1);
        }
        
        String result;
        if (stdout.startsWith("failing tests:")) {
            BufferedReader reader = new BufferedReader(new StringReader(stdout));
            reader.readLine();
            Set<String> failingTests = new HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                failingTests.add(line);
            }
            LOG.info(() -> "[" + bug + "] Failing tests detected by geneseer:   "
                    + failingTests.stream().sorted().toList());
            
            result = checkFailingTests(bug, failingTests);
        } else {
            result = stdout.replace('\n', ' ').replace('\r', ' ');
        }
        
        if (ProcessRunner.untilNoInterruptedException(() -> process.waitFor()) != 0) {
            if (result.isEmpty()) {
                throw new IOException("Failed to run geneseer on " + bug);
            } else {
                LOG.warning(() -> "[" + bug + "] Exit code of geneseer: " + process.exitValue());
            }
        } else {
            String r = result;
            LOG.info(() ->  "[" + bug + "] Completed: " + r);
        }
        return bug.project() + ";" + bug.bug() + ";" + result;
    }

    private String checkFailingTests(Bug bug, Set<String> failingTests) throws IOException {
        String result;
        Set<String> defects4jFailingTests = defects4j.getFailingTests(bug);
        LOG.info(() -> "[" + bug + "] Failing tests according to defects4j: "
                + defects4jFailingTests.stream().sorted().toList());
        if (failingTests.equals(defects4jFailingTests)) {
            result = "matching failing tests";
        } else {
            result = "mismatching failing tests";
            for (String test : failingTests) {
                if (!defects4jFailingTests.contains(test)) {
                    result += ";+" + test;
                }
            }
            for (String test : defects4jFailingTests) {
                if (!failingTests.contains(test)) {
                    result += ";-" + test;
                }
            }
        }
        return result;
    }
    
    private String runOnSingleBug(Bug bug) throws IOException, IllegalArgumentException {
        LOG.info(() -> "[" + bug + "] Preparing project...");
        Project config = defects4j.prepareProject(bug);
        
        LOG.info(() -> "[" + bug + "] Running geneseer...");
        List<String> command = new LinkedList<>();
        command.add(jvmExecutable);
        command.add("-Djava.util.logging.config.class=" + LoggingConfiguration.class.getName());
        command.add("-Djava.util.logging.config.file=logging.properties");
        command.add("-Dfile.encoding=" + bug.getEncoding().name());
        command.add("-cp");
        command.add(geneseerJar);
        command.add(Geneseer.class.getName());
        command.add(bug.getDirectory().toString());
        command.add(config.getSourceDirectory().toString());
        command.add(config.getCompilationClasspath().stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)));
        command.add(config.getTestExecutionClassPath().stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)));
        command.addAll(config.getTestClassNames());
    
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(bug.getDirectory().resolve("geneseer.log").toFile());
        pb.redirectOutput(Redirect.PIPE);
        Process process = pb.start();
        
        String stdout = new String(process.getInputStream().readAllBytes());
        if (stdout.endsWith("\n")) {
            stdout = stdout.substring(0, stdout.length() - 1);
        }
        String result = null;
        if (stdout.indexOf(';') != -1) {
            result = stdout.substring(0, stdout.indexOf(';'));
        }
        
        if (ProcessRunner.untilNoInterruptedException(() -> process.waitFor()) != 0) {
            if (stdout.isEmpty()) {
                throw new IOException("Failed to run geneseer on " + bug);
            } else {
                LOG.warning(() -> "[" + bug + "] Exit code of geneseer: " + process.exitValue());
            }
        } else {
            String r = result;
            LOG.info(() ->  "[" + bug + "] Completed" + (r != null ? (": " + r) : ""));
        }
        
        return bug.project() + ";" + bug.bug() + ";" + stdout;
    }
    
    public void setJvmExecutable(String jvmExecutable) {
        this.jvmExecutable = jvmExecutable;
    }
    
    public void setGeneseerJar(String geneseerJar) {
        this.geneseerJar = geneseerJar;
    }
    
    public void setDefects4jPath(Path defects4jHome) {
        this.defects4j = new Defects4jWrapper(defects4jHome);
    }
    
    public void setTarget(Target target) {
        this.target = target;
    }
    
    public void setNumThreads(int numThreads) {
        this.numThreads = numThreads;
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

    public void run() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        bugsFinished = 0;
        totalBugRuntimeInSeconds = 0;
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", Locale.ROOT);
        Path outputPath = Path.of("output_" + formatter.format(now) + ".csv");
        LOG.info(() -> "Writing output to " + outputPath);
        
        try (Writer writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            LOG.info(() -> "Starting " + numThreads + " threads...");
            List<Thread> threads = new LinkedList<>();
            for (int i = 0; i < numThreads; i++) {
                Thread th = new Thread(() -> {
                    Bug bug;
                    while ((bug = nextBug()) != null) {
                        long t0 = System.currentTimeMillis();
                        try {
                            String output;
                            if (target == Target.SETUP_TEST) {
                                output = runSetupTest(bug);
                            } else {
                                output = runOnSingleBug(bug);
                            }
                            synchronized (writer) {
                                writer.write(output + "\n");
                                writer.flush();
                            }
                        } catch (IOException | IllegalArgumentException e) {
                            LOG.log(Level.SEVERE, "[" + bug + "] Failed to run", e);
                        } finally {
                            long t1 = System.currentTimeMillis();
                            
                            long runtimeSeconds = (t1 - t0) / 1000;
                            synchronized (this) {
                                bugsFinished++;
                                totalBugRuntimeInSeconds += runtimeSeconds;
                            }
                        }
                    }
                });
                th.start();
                threads.add(th);
            }
            
            threads.forEach(t -> ProcessRunner.untilNoInterruptedException(() -> {
                t.join();
                return 0;
            }));
            LOG.info("All threads done");
        }
    }
    
    public static void main(String[] args) throws IOException {
        CliArguments cli = new CliArguments(args, Set.of(
                "--jvm", "--geneseer-jar", "--defects4j", "--target", "--threads"));
        
        Orchestrator orchestrator = new Orchestrator();
        orchestrator.setJvmExecutable(cli.getOptionOrThrow("--jvm"));
        orchestrator.setGeneseerJar(cli.getOptionOrThrow("--geneseer-jar"));
        orchestrator.setDefects4jPath(Path.of(cli.getOptionOrThrow("--defects4j")));
        orchestrator.setTarget(Target.valueOf(cli.getOption("--target", Target.GENESEER.name())));
        orchestrator.setNumThreads(Integer.parseInt(cli.getOption("--threads", "1")));

        if (cli.getRemaining().isEmpty()) {
            orchestrator.selectAllBugs();
            
        } else {
            List<Bug> bugs = new LinkedList<>();
            for (String arg : cli.getRemaining()) {
                String[] parts = arg.split("/");
                if (parts[1].equals("*")) {
                    bugs.addAll(orchestrator.getBugsOfProject(parts[0]));
                } else {
                    bugs.add(new Bug(parts[0], Integer.parseInt(parts[1])));
                }
            }
            orchestrator.selectBugs(bugs);
        }
        
        orchestrator.run();
    }
    
}
