package net.ssehub.program_repair.geneseer.orchestrator;

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
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.Geneseer;
import net.ssehub.program_repair.geneseer.LlmQueryAnalysis;
import net.ssehub.program_repair.geneseer.OnlyDelete;
import net.ssehub.program_repair.geneseer.Project;
import net.ssehub.program_repair.geneseer.PureLlmFixer;
import net.ssehub.program_repair.geneseer.SetupTest;
import net.ssehub.program_repair.geneseer.logging.LoggingConfiguration;
import net.ssehub.program_repair.geneseer.util.CliArguments;

public class Defects4jRunner {

    static {
        System.setProperty("java.util.logging.config.class", LoggingConfiguration.class.getName());
    }
    
    private static final Logger LOG = Logger.getLogger(Defects4jRunner.class.getName());
    
    public enum Target {
        GENESEER(Geneseer.class, null),
        @SuppressWarnings("unchecked")
        SETUP_TEST(SetupTest.class, (runner, stdout) -> {
            Gson gson = new Gson();
            
            String result = stdout;
            try {
                Map<Object, Object> parsed = gson.fromJson(stdout, Map.class);
                
                List<Map<Object, Object>> failingTests = (List<Map<Object, Object>>) parsed.get("failingTests");
                
                Set<String> actualFailingTests = new HashSet<>(failingTests.size());
                for (Map<Object, Object> failingTest : failingTests) {
                    actualFailingTests.add(failingTest.get("class") + "::" + failingTest.get("method"));
                }
                
                Set<String> expectedFailingTests = runner.defects4j.getFailingTests(runner.bug);
                
                if (expectedFailingTests.equals(actualFailingTests)) {
                    parsed.put("matchingDefects4j", true);
                } else {
                    parsed.put("matchingDefects4j", false);
                }
                
                result = gson.toJson(parsed);
            } catch (JsonParseException | NullPointerException | IOException e) {
                LOG.log(Level.WARNING, "Could not determine if failing tests were correct", e);
            }
            return result;
        }),
        ONLY_DELETE(OnlyDelete.class, null),
        PURE_LLM(PureLlmFixer.class, null),
        LLM_QUERY_ANALYSIS(LlmQueryAnalysis.class, null);
        
        private Class<?> mainClass;
        
        private BiFunction<Defects4jRunner, String, String> postProcessor;
        
        private Target(Class<?> mainClass, BiFunction<Defects4jRunner, String, String> postProcessor) {
            this.mainClass = mainClass;
            this.postProcessor = postProcessor;
        }

        public void run(Defects4jRunner runner, String[] args) {
            ByteArrayOutputStream capturedStdout = new ByteArrayOutputStream();
            PrintStream sysout = System.out;
            if (postProcessor != null) {
                System.setOut(new PrintStream(capturedStdout));
            }
            
            try {
                mainClass.getMethod("main", String[].class).invoke(null, (Object) args);
                
                if (postProcessor != null) {
                    System.out.flush();
                    String caputured = capturedStdout.toString();
                    sysout.print(postProcessor.apply(runner, caputured));
                }
                
            } catch (ReflectiveOperationException e) {
                LOG.log(Level.SEVERE, "Cannot execute main method of target", e);
            }
        }
        
    }
    
    private Target target = Target.GENESEER;
    
    private Defects4jWrapper defects4j;
    
    private Path configurationFile;
    
    private Bug bug;
    
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
        
        if (configurationFile != null) {
            command.add("--config");
            command.add(configurationFile.toString());
        }
        
        return command;
    }
    
    public void setDefects4jPath(Path defects4jHome) {
        this.defects4j = new Defects4jWrapper(defects4jHome);
    }
    
    public void setTarget(Target target) {
        this.target = target;
    }
    
    public List<Bug> getBugsOfProject(String project) throws IllegalArgumentException, IOException {
        return defects4j.getBugsOfProject(project);
    }
    
    public void setBug(Bug bug) {
        this.bug = bug;
    }
    
    public void setConfigurationFile(Path configurationFile) {
        this.configurationFile = configurationFile;
    }

    public void run() throws IOException {
        LOG.info(() -> "Running on bug " + bug);
        LOG.info("Preparing project...");
        Project config = defects4j.prepareProject(bug);
        
        List<String> args = buildArgs(bug.getDirectory(), config);
        
        target.run(this, args.toArray(String[]::new));
    }
    
    public static void main(String[] args) throws IOException {
        CliArguments cli = new CliArguments(args, Set.of(
                "--defects4j", "--target", "--config"));
        
        Defects4jRunner runner = new Defects4jRunner();
        runner.setDefects4jPath(Path.of(cli.getOptionOrThrow("--defects4j")));
        runner.setTarget(Target.valueOf(cli.getOption("--target", Target.GENESEER.name())));
        if (cli.hasOption("--config")) {
            runner.setConfigurationFile(Path.of(cli.getOption("--config")));
        }
        
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
