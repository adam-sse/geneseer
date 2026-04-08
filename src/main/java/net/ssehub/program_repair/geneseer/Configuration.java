package net.ssehub.program_repair.geneseer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.util.CliArguments;

public class Configuration {

    public static final Configuration INSTANCE = new Configuration();
    
    private static final Logger LOG = Logger.getLogger(Configuration.class.getName());
    
    public static class Section {
        
        private String key;
        
        private String name;
        
        private List<Option<?>> options;
        
        public Section(String key, String name, List<Option<?>> options) {
            this.key = key;
            this.name = name;
            this.options = options;
        }
        
        public Option<?> getOption(String key) {
            return options.stream().filter(o -> o.key.equals(key)).findFirst().orElse(null);
        }
        
    }
    
    public static class Option<T> {
        
        private String key;
        
        private T value;
        
        private boolean changed;
        
        private Function<String, T> valueParser;
        
        private String name;
        
        public Option(String key, String name, T defaultValue, Function<String, T> valueParser) {
            this.key = key;
            this.name = name;
            this.value = defaultValue;
            this.valueParser = valueParser;
        }
        
        public Option(String key, String name, Function<String, T> valueParser) {
            this.key = key;
            this.name = name;
            this.valueParser = valueParser;
        }
        
        public void setValue(T value) {
            this.changed = true;
            this.value = value;
        }
        
        public void setValueFromString(String value) {
            setValue(valueParser.apply(value));
        }
        
        public T getValue() {
            return value;
        }
        
        protected String valueAsString() {
            return value.toString();
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name);
            if (changed) {
                sb.append("*");
            }
            sb.append(": ");
            if (value == null) {
                sb.append("<not set>");
            } else {
                sb.append(valueAsString());
            }
            return sb.toString();
        }
        
    }
    
    public static class SetupConfiguration extends Section {
        
        private Option<String> fixer = new Option<>("fixer",
                "Fixer to use", "GENETIC_ALGORITHM", s -> s.toUpperCase());
        private Option<String> jvmBinaryPath = new Option<>("jvmBinaryPath",
                "JVM binary path", "java", Function.identity());
        private Option<String> javaCompilerBinaryPath = new Option<>("javaCompilerBinaryPath",
                "Java compiler binary path", "javac", Function.identity());
        private Option<Integer> testExecutionTimeoutMs = new Option<>("testExecutionTimeoutMs",
                "Test execution timeout", (int) TimeUnit.MINUTES.toMillis(2), Integer::parseInt);
        private Option<Boolean> coverageMatrixSimplified = new Option<>("coverageMatrixSimplified",
                "Simplified coverage matrix", true, Boolean::parseBoolean);
        private Option<Double> suspiciousnessThreshold = new Option<>("suspiciousnessThreshold",
                "Suspiciousness threshold", 0.01, Double::parseDouble);
        private Option<Integer> suspiciousStatementLimit = new Option<>("suspiciousStatementLimit",
                "Suspicious statement limit", 500, Integer::parseInt);
        private Option<TestsToRun> testsToRun = new Option<>("testsToRun",
                "Tests to run", TestsToRun.ALL_TESTS, v -> TestsToRun.valueOf(v.toUpperCase()));
        private Option<Boolean> debugTestDriver = new Option<>("debugTestDriver",
                "Debug test driver", false, Boolean::parseBoolean);
        
        public enum TestsToRun {
            ALL_TESTS, RELEVANT_TESTS
        }
        
        public SetupConfiguration() {
            super("setup", "Setup Configuration", new LinkedList<>());
            super.options.add(fixer);
            super.options.add(jvmBinaryPath);
            super.options.add(javaCompilerBinaryPath);
            super.options.add(testExecutionTimeoutMs);
            super.options.add(coverageMatrixSimplified);
            super.options.add(suspiciousnessThreshold);
            super.options.add(suspiciousStatementLimit);
            super.options.add(testsToRun);
            super.options.add(debugTestDriver);
        }
        
        public String fixer() {
            return fixer.getValue();
        }
        
        public String jvmBinaryPath() {
            return jvmBinaryPath.getValue();
        }
        
        public String javaCompilerBinaryPath() {
            return javaCompilerBinaryPath.getValue();
        }
        
        public int testExecutionTimeoutMs() {
            return testExecutionTimeoutMs.getValue();
        }
        
        public boolean coverageMatrixSimplified() {
            return coverageMatrixSimplified.getValue();
        }
        
        public double suspiciousnessThreshold() {
            return suspiciousnessThreshold.getValue();
        }
        
        public int suspiciousStatementLimit() {
            return suspiciousStatementLimit.getValue();
        }
        
        public TestsToRun testsToRun() {
            return testsToRun.getValue();
        }
        
        public boolean debugTestDriver() {
            return debugTestDriver.getValue();
        }
        
    }
    
    public static class GeneticConfiguration extends Section {

        private Option<Long> randomSeed = new Option<>("randomSeed",
                "Random seed", 0L, Long::parseLong);
        private Option<Integer> populationSize = new Option<>("populationSize",
                "Population size", 40, Integer::parseInt);
        private Option<Integer> generationLimit = new Option<>("generationLimit",
                "Generation limit", 10, Integer::parseInt);
        private Option<Double> negativeTestsWeight = new Option<>("negativeTestsWeight",
                "Negative tests weight", 10.0, Double::parseDouble);
        private Option<Double> positiveTestsWeight = new Option<>("positiveTestsWeight",
                "Positive tests weight", 1.0, Double::parseDouble);
        private Option<Double> mutationProbability = new Option<>("mutationProbability",
                "Mutation probability", 0.5, Double::parseDouble);
        private Option<Double> llmMutationProbability = new Option<>("llmMutationProbability",
                "LLM-mutation probability", 0.0, Double::parseDouble);
        private Option<MutationScope> statementScope = new Option<>("statementScope",
                "Statement Scope", MutationScope.GLOBAL, v -> MutationScope.valueOf(v.toUpperCase()));
        
        public enum MutationScope {
            GLOBAL, FILE
        }
        
        public GeneticConfiguration() {
            super("genetic", "Genetic Configuration", new LinkedList<>());
            super.options.add(randomSeed);
            super.options.add(populationSize);
            super.options.add(generationLimit);
            super.options.add(negativeTestsWeight);
            super.options.add(positiveTestsWeight);
            super.options.add(mutationProbability);
            super.options.add(llmMutationProbability);
            super.options.add(statementScope);
        }
        
        public long randomSeed() {
            return randomSeed.getValue();
        }
        
        public int populationSize() {
            return populationSize.getValue();
        }
        
        public int generationLimit() {
            return generationLimit.getValue();
        }
        
        public double negativeTestsWeight() {
            return negativeTestsWeight.getValue();
        }
        
        public double positiveTestsWeight() {
            return positiveTestsWeight.getValue();
        }
        
        public double mutationProbability() {
            return mutationProbability.getValue();
        }
        
        public double llmMutationProbability() {
            return llmMutationProbability.getValue();
        }
        
        public MutationScope statementScope() {
            return statementScope.getValue();
        }
        
    }
    
    public static class LlmConfiguration extends Section {
        
        private Option<String> model = new Option<>("model",
                "Model", "dummy", Function.identity());
        private Option<String> api = new Option<>("api", "API", Function.identity());
        private Option<String> apiToken = new Option<>("apiToken",
                "API Token", Function.identity()) {
            @Override
            protected String valueAsString() {
                return "<redacted>";
            };
        };
        private Option<Long> timeoutMs = new Option<>("timeoutMs", "Timeout",
                TimeUnit.MINUTES.toMillis(30), Long::parseLong);
        private Option<String> think = new Option<>("think", "Thinking", Function.identity());
        private Option<String> thinkingDelimiter = new Option<>("thinkingDelimiter", "Thinking Delimiter",
                Function.identity());
        private Option<Double> temperature = new Option<>("temperature", "Temperature", Double::parseDouble);
        private Option<Long> contextSize = new Option<>("contextSize", "Context window size", Long::parseLong);
        private Option<Long> seed = new Option<>("seed", "Seed", Long::parseLong);
        private Option<CodeContextSelection> codeContextSelection = new Option<>("codeContextSelection",
                "Method for selecting code context", CodeContextSelection.SUSPICIOUSNESS,
                v -> CodeContextSelection.valueOf(v.toUpperCase()));
        public enum CodeContextSelection {
            SUSPICIOUSNESS, RAG, LLM
        }
        private Option<Integer> maxCodeContext = new Option<>("maxCodeContext", "Max code context lines", 100,
                Integer::parseInt);
        private Option<ProjectOutline> projectOutline = new Option<>("projectOutline",
                "Project outline in prompt", ProjectOutline.PARTIAL, v -> ProjectOutline.valueOf(v.toUpperCase()));
        public enum ProjectOutline {
            FULL, PARTIAL, NONE
        }
        private Option<Boolean> structuredOutput = new Option<>("structuredOutput",
                "Structured output", false, Boolean::parseBoolean);
        
        public LlmConfiguration() {
            super("llm", "LLM Configuration", new LinkedList<>());
            super.options.add(model);
            super.options.add(api);
            super.options.add(apiToken);
            super.options.add(timeoutMs);
            super.options.add(think);
            super.options.add(thinkingDelimiter);
            super.options.add(temperature);
            super.options.add(contextSize);
            super.options.add(seed);
            super.options.add(codeContextSelection);
            super.options.add(maxCodeContext);
            super.options.add(projectOutline);
            super.options.add(structuredOutput);
        }
        
        public String model() {
            return model.getValue();
        }
        
        public String api() {
            return api.getValue();
        }
        
        public String apiToken() {
            return apiToken.getValue();
        }
        
        public long timeoutMs() {
            return timeoutMs.getValue();
        }
        
        public String think() {
            return think.getValue();
        }
        
        public String thinkingDelimiter() {
            return thinkingDelimiter.getValue();
        }
        
        public Double temperature() {
            return temperature.getValue();
        }
        
        public Long contextSize() {
            return contextSize.getValue();
        }
        
        public Long seed() {
            return seed.getValue();
        }
        
        public CodeContextSelection codeContextSelection() {
            return codeContextSelection.getValue();
        }
        
        public int maxCodeContext() {
            return maxCodeContext.getValue();
        }
        
        public ProjectOutline projectOutine() {
            return projectOutline.getValue();
        }
        
        public boolean structuredOutput() {
            return structuredOutput.getValue();
        }
    }
    
    public static class RagConfiguration extends Section {
        
        private Option<String> chromadbWorkerPythonBinaryPath = new Option<>("chromadbWorkerPythonBinaryPath",
                "Python binary path", Function.identity());
        private Option<String> model = new Option<>("model",
                "Model", Function.identity());
        private Option<URL> api = new Option<>("api", "API", s -> {
            try {
                return new URL(s);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        });
        private Option<Boolean> persist = new Option<>("persist",
                "Persistent RAG database", false, Boolean::parseBoolean);
        
        public RagConfiguration() {
            super("rag", "RAG Configuration", new LinkedList<>());
            super.options.add(chromadbWorkerPythonBinaryPath);
            super.options.add(model);
            super.options.add(api);
            super.options.add(persist);
        }
        
        public String chromadbWorkerPythonBinaryPath() {
            return chromadbWorkerPythonBinaryPath.getValue();
        }
        
        public String model() {
            return model.getValue();
        }
        
        public URL api() {
            return api.getValue();
        }
        
        public boolean persist() {
            return persist.getValue();
        }
        
    }
    
    private SetupConfiguration setup = new SetupConfiguration();
    private GeneticConfiguration genetic = new GeneticConfiguration();
    private LlmConfiguration llm = new LlmConfiguration();
    private RagConfiguration rag = new RagConfiguration();
    
    private List<Section> sections = List.of(setup, genetic, llm, rag);
    
    private Section getSection(String key) {
        return sections.stream().filter(s -> s.key.equals(key)).findFirst().orElse(null);
    }
    
    public void loadFromCli(CliArguments args) throws IllegalArgumentException {
        for (String cliOption : getCliOptions()) {
            if (args.hasOption(cliOption)) {
                String[] parts = cliOption.split("\\.");
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Invalid CLI config key: " + cliOption);
                }
                
                Option<?> option = null;
                Section section = getSection(parts[1]);
                if (section != null) {
                    option = section.getOption(parts[2]);
                }
                if (option != null) {
                    option.setValueFromString(args.getOption(cliOption));
                } else {
                    LOG.warning(() -> "Unknown configuration key " + cliOption);
                }
            }
        }
        
    }
    
    public void log() {
        for (Section section : sections) {
            LOG.config(() -> section.name + ":");
            for (Option<?> option : section.options) {
                LOG.config(() -> "    " + option.toString());
            }
        }
    }
    
    public Set<String> getCliOptions() {
        Set<String> options = new HashSet<>();
        for (Section section : sections) {
            for (Option<?> option : section.options) {
                options.add("--config." + section.key + "." + option.key);
            }
        }
        return options;
    }
    
    public String getCliUsage() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Section section : sections) {
            for (Option<?> option : section.options) {
                if (first) {
                    first = false;
                } else {
                    sb.append(' ');
                }
                sb.append("[--config.").append(section.key).append('.').append(option.key).append(" <value>]");
            }
        }
        return sb.toString();
    }
    
    public SetupConfiguration setup() {
        return setup;
    }
    
    public GeneticConfiguration genetic() {
        return genetic;
    }
    
    public LlmConfiguration llm() {
        return llm;
    }
    
    public RagConfiguration rag() {
        return rag;
    }
    
}
