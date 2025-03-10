package net.ssehub.program_repair.geneseer.defects4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.CliArguments;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.ProcessManager;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class Defects4jJGenProgRunner {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Defects4jJGenProgRunner.class.getName());
    
    private Defects4jWrapper defects4j;
    
    private Bug bug;
    
    public void setDefects4jPath(Path defects4jHome) {
        this.defects4j = new Defects4jWrapper(defects4jHome);
    }
    
    public void setBug(Bug bug) {
        this.bug = bug;
    }
    
    public void run() throws IOException, IllegalArgumentException {
        LOG.info(() -> "Running on bug " + bug);
        LOG.info("Preparing project");
        Project config = defects4j.prepareProject(bug);
        
        
        List<String> args = new LinkedList<>();
        args.add("-mode");
        args.add("jgenprog");

        args.add("-parameters");
        args.add(parameters());
        
        args.add("-location");
        args.add(config.getProjectDirectory().toString());
        
        args.add("-srcjavafolder");
        args.add(config.getSourceDirectory().toString());
        args.add("-binjavafolder");
        args.add(defects4j.getRelativeBinDirectory(config.getProjectDirectory()).toString());
        args.add("-srctestfolder");
        args.add(defects4j.getRelativeTestSourceDirectory(config.getProjectDirectory()).toString());
        args.add("-bintestfolder");
        args.add(defects4j.getRelativeTestBinDirectory(config.getProjectDirectory()).toString());
        
        args.add("-dependencies");
        args.add(config.getCompilationClasspathAbsolute().stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)));
        
        args.add("-failing");
        args.add(defects4j.getFailingTests(bug).stream()
                .map(s -> s.split("::")[0])
                .collect(Collectors.joining(File.pathSeparator)));
        
        args.add("-jvm4testexecution");
        args.add("/usr/lib/jvm/java-8-openjdk-amd64/bin"); // TODO
        
        args.add("-javacompliancelevel");
        args.add("8");
        
        LOG.info("Running jGenProg");
        LOG.info("Args:");
        for (int i = 0; i < args.size(); i += 2) {
            LOG.info("  " + args.get(i) + " " + args.get(i + 1));
        }
        
        args.add(0, "/usr/lib/jvm/java-1.17.0-openjdk-amd64/bin/java");
        args.add(1, "-cp");
        args.add(2, Path.of("astor.jar").toAbsolutePath().toString());
        args.add(3, "fr.inria.main.evolution.AstorMain");
        
        try {
            int exitCode = runProcess(config, args);
            LOG.info("jGenProg finished with exit code " + exitCode);
            
            handleOutput(config);
        } finally {
            for (Path dir : List.of(config.getProjectDirectory().resolve("output_astor"),
                    config.getProjectDirectory().resolve("diffSolutions"))) {
                if (Files.exists(dir)) {
                    try {
                        FileUtils.deleteDirectory(dir);
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, "Failed to delete astor output directory " + dir, e);
                    }
                }
            }
        }
    }

    private String parameters() {
        List<String> parameters = new LinkedList<>();
        
        parameters.add("stopfirst");
        parameters.add("true");
        
        parameters.add("maxGeneration");
        parameters.add(Integer.toString(Configuration.INSTANCE.genetic().generationLimit()));
        
        parameters.add("population");
        parameters.add(Integer.toString(Configuration.INSTANCE.genetic().populationSize()));
        
        switch (Configuration.INSTANCE.genetic().statementScope()) {
        case FILE:
            parameters.add("scope");
            parameters.add("local");
            break;
        case GLOBAL:
            parameters.add("scope");
            parameters.add("global");
            break;
        default:
            break;
        }
        
        parameters.add("applyCrossover");
        parameters.add(Boolean.toString(Configuration.INSTANCE.genetic().populationSize() != 1)); // TODO
        
        parameters.add("maxtime");
        parameters.add("180"); // TODO
        
        parameters.add("maxsuspcandidates");
        parameters.add(Integer.toString(Configuration.INSTANCE.setup().suspiciousStatementLimit()));
        
        parameters.add("maxsuspcandidates");
        parameters.add(Integer.toString(Configuration.INSTANCE.setup().suspiciousStatementLimit()));
        
        parameters.add("flthreshold");
        parameters.add(Double.toString(Configuration.INSTANCE.setup().suspiciousnessThreshold()));
        
        parameters.add("loglevel");
        parameters.add("INFO");
        
        return parameters.stream().collect(Collectors.joining(":"));
    }

    private int runProcess(Project config, List<String> args) throws IOException {
        try (TemporaryDirectoryManager tempDirManager = new TemporaryDirectoryManager("jgenprog")) {
            Path tempDir = tempDirManager.createTemporaryDirectory();
            args.add(3, "-Djava.io.tmpdir=" + tempDir);
            
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(config.getProjectDirectory().toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("_JAVA_OPTIONS", "-Djava.io.tmpdir=" + tempDir);
            Process p = pb.start();
            ProcessManager.INSTANCE.trackProcess(p);
            p.getOutputStream().close();
            byte[] buffer = new byte[255];
            int read;
            while ((read = p.getInputStream().read(buffer)) != -1) {
                System.err.write(buffer, 0, read);
            }
            
            int exitCode = -1;
            boolean success = false;
            while (!success) {
                try {
                    exitCode = p.waitFor();
                    success = true;
                } catch (InterruptedException e) {
                }
            }
            return exitCode;
        }
    }
    
    private void handleOutput(Project config) throws IOException {
        Path outputFolder = config.getProjectDirectory().resolve("output_astor");
        List<Path> subdirs = Files.list(outputFolder).toList();
        if (subdirs.size() != 1) {
            throw new IOException("Folder " + outputFolder + " contains more than one child");
        }
        outputFolder = subdirs.get(0);
        Path outputFile = outputFolder.resolve("astor_output.json");
        
        if (!Files.isRegularFile(outputFile)) {
            throw new IOException("File " + outputFile + " does not exist");
        }
        
        String output = Files.readString(outputFile, StandardCharsets.UTF_8);
        
        Gson gson = new Gson();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> json = gson.fromJson(output, Map.class);
            if (json.containsKey("general") && json.get("general") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> general = (Map<String, Object>) json.get("general");
                json.put("result", general.get("OUTPUT_STATUS"));
                output = gson.toJson(json);
            }
        } catch (JsonParseException e) {
            LOG.log(Level.WARNING, "Unable to parse JSON output", e);
        }
        
        System.out.print(output);
    }

    public static void main(String[] args) throws IOException {
        Set<String> cliOptions = new HashSet<>();
        cliOptions.add("--defects4j");
        cliOptions.addAll(Configuration.INSTANCE.getCliOptions());
        CliArguments cli = new CliArguments(args, cliOptions);
        
        Configuration.INSTANCE.loadFromCli(cli);
        Configuration.INSTANCE.log();
        
        Defects4jJGenProgRunner runner = new Defects4jJGenProgRunner();
        runner.setDefects4jPath(Path.of(cli.getOptionOrThrow("--defects4j")));
        
        if (cli.getRemaining().size() == 1) {
            String[] parts = cli.getRemaining().get(0).split("/");
            Bug bug = new Bug(parts[0], Integer.parseInt(parts[1]));
            runner.setBug(bug);
            runner.run();
            
        } else {
            LOG.severe("Expecting exactly one bug as parameter");
            System.exit(1);
        }
    }
    
}
