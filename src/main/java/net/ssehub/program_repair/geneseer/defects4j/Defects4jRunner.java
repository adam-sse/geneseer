package net.ssehub.program_repair.geneseer.defects4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
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
import net.ssehub.program_repair.geneseer.Geneseer;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.CliArguments;

public class Defects4jRunner {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Defects4jRunner.class.getName());
    
    private Defects4jWrapper defects4j;
    
    private Bug bug;
    
    private CliArguments cliArguments;
    
    private List<String> buildArgs(Path checkoutDirectory, Project config) {
        List<String> command = new LinkedList<>();
        command.add("--project-directory");
        command.add(checkoutDirectory.toString());
        command.add("--source-directory");
        command.add(config.getSourceDirectory().toString());
        
        if (config.getEncoding() != Charset.defaultCharset()) {
            command.add("--encoding");
            command.add(config.getEncoding().toString());
        }
        
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
        
        for (String configOption : Configuration.INSTANCE.getCliOptions()) {
            if (cliArguments.hasOption(configOption)) {
                command.add(configOption);
                command.add(cliArguments.getOption(configOption));
            }
        }
        
        return command;
    }
    
    public void setDefects4jPath(Path defects4jHome) {
        this.defects4j = new Defects4jWrapper(defects4jHome);
    }
    
    public List<Bug> getBugsOfProject(String project) throws IllegalArgumentException, IOException {
        return defects4j.getBugsOfProject(project);
    }
    
    public void setBug(Bug bug) {
        this.bug = bug;
    }
    
    public void setCliArguments(CliArguments cliArguments) {
        this.cliArguments = cliArguments;
    }
    
    public void run() throws IOException, IllegalArgumentException {
        LOG.info(() -> "Running on bug " + bug);
        LOG.info("Preparing project");
        Project config = defects4j.prepareProject(bug);
        
        List<String> args = buildArgs(bug.getDirectory(), config);
        
        boolean isSetupTest = Configuration.INSTANCE.setup().getFixer().equals("SETUP_TEST");
        ByteArrayOutputStream capturedStdout = new ByteArrayOutputStream();
        PrintStream sysout = System.out;
        if (isSetupTest) {
            System.setOut(new PrintStream(capturedStdout));
        }
        
        LOG.info("Running Geneseer");
        Geneseer.main(args.toArray(s -> new String[s]));
        LOG.info("Geneseer finished");
        
        if (isSetupTest) {
            System.out.flush();
            String caputured = capturedStdout.toString();
            sysout.print(augmentSetupTestOutput(caputured));
        }
    }
    
    private String augmentSetupTestOutput(String stdout) {
        Gson gson = new Gson();
        
        LOG.fine("Augmenting SETUP_TEST result with expected failing tests from Defects4j");
        String result = stdout;
        try {
            @SuppressWarnings("unchecked")
            Map<Object, Object> json = gson.fromJson(stdout, Map.class);
            String jsonResult = (String) json.get("result");
            if (jsonResult.equals("FOUND_FAILING_TESTS") || jsonResult.equals("NO_FAILING_TESTS")) {
                
                @SuppressWarnings("unchecked")
                List<Map<Object, Object>> failingTests = (List<Map<Object, Object>>) json.get("failingTests");
                
                Set<String> actualFailingTests = new HashSet<>(failingTests.size());
                for (Map<Object, Object> failingTest : failingTests) {
                    actualFailingTests.add(failingTest.get("class") + "::" + failingTest.get("method"));
                }
                
                Set<String> expectedFailingTests = defects4j.getFailingTests(bug);
                
                if (expectedFailingTests.equals(actualFailingTests)) {
                    json.put("result", "MATCHING_DEFECTS4J");
                } else {
                    json.put("result", "NOT_MATCHING_DEFECTS4J");
                }
                result = gson.toJson(json);
                
            } else {
                LOG.warning("Result from SETUP_TEST is neither FOUND_FAILING_TESTS nor NO_FAILING_TESTS");
            }
        } catch (JsonParseException | NullPointerException | IOException e) {
            LOG.log(Level.WARNING, "Could not determine if failing tests were correct", e);
        }
        
        return result;
    }
    
    public static void main(String[] args) throws IOException {
        Set<String> cliOptions = new HashSet<>();
        cliOptions.add("--defects4j");
        cliOptions.add("--target");
        cliOptions.addAll(Configuration.INSTANCE.getCliOptions());
        CliArguments cli = new CliArguments(args, cliOptions);
        
        Defects4jRunner runner = new Defects4jRunner();
        runner.setDefects4jPath(Path.of(cli.getOptionOrThrow("--defects4j")));
        runner.setCliArguments(cli);
        
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
