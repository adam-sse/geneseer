package net.ssehub.program_repair.geneseer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused") // GSON uses direct field access
public class Result {

    private String result;
    private Integer generation;
    private String exception;
    
    public class Fitness {
        private Double original;
        private Double max;
        private Double best;
        public void setOriginal(double original) {
            synchronized (Result.this) {
                this.original = original;
            }
        }
        public void setMax(double max) {
            synchronized (Result.this) {
                this.max = max;
            }
        }
        public void setBest(double best) {
            synchronized (Result.this) {
                this.best = best;
            }
        }
    }
    private Fitness fitness;
    
    public class Patch {
        private String diff;
        private List<String> mutations;
        private Integer addedLines;
        private Integer removedLines;
        public void setDiff(String diff) {
            synchronized (Result.this) {
                this.diff = diff;
            }
        }
        public void setMutations(List<String> mutations) {
            synchronized (Result.this) {
                this.mutations = List.copyOf(mutations);
            }
        }
        public void setAddedLines(int addedLines) {
            synchronized (Result.this) {
                this.addedLines = addedLines;
            }
        }
        public void setRemovedLines(int removedLines) {
            synchronized (Result.this) {
                this.removedLines = removedLines;
            }
        }
    }
    private Patch patch;

    public class AstStats {
        private Integer nodes;
        private Integer statements;
        private Integer suspicious;
        public void setNodes(int nodes) {
            synchronized (Result.this) {
                this.nodes = nodes;
            }
        }
        public void setStatements(int statements) {
            synchronized (Result.this) {
                this.statements = statements;
            }
        }
        public void setSuspicious(int suspicious) {
            synchronized (Result.this) {
                this.suspicious = suspicious;
            }
        }
    }
    private AstStats ast;
    
    public class EvaluationStats {
        private int compilations;
        private int testSuiteRuns;
        private Integer initialPassingTestCases;
        private Integer initialFailingTestCases;
        public void increaseCompilations() {
            synchronized (Result.this) {
                compilations++;
            }
        }
        public void increaseTestSuiteRuns() {
            synchronized (Result.this) {
                testSuiteRuns++;
            }
        }
        public void setInitialPassingTestCases(Integer initialPassingTestCases) {
            synchronized (Result.this) {
                this.initialPassingTestCases = initialPassingTestCases;
            }
        }
        public void setInitialFailingTestCases(Integer initialFailingTestCases) {
            synchronized (Result.this) {
                this.initialFailingTestCases = initialFailingTestCases;
            }
        }
    }
    private EvaluationStats evaluations;
    
    public class MutationStats {
        private int insertions;
        private int deletions;
        private int replacements;
        private int failedMutations;
        private int successfulCrossovers;
        private int failedCrossovers;
        private int llmCallsOnUnmodified;
        private int llmCallsOnMutated;
        public void increaseInsertions() {
            synchronized (Result.this) {
                insertions++;
            }
        }
        public void increaseDeletions() {
            synchronized (Result.this) {
                deletions++;
            }
        }
        public void increaseReplacements() {
            synchronized (Result.this) {
                replacements++;
            }
        }
        public void increaseFailedMutations() {
            synchronized (Result.this) {
                failedMutations++;
            }
        }
        public void increaseSuccessfulCrossovers() {
            synchronized (Result.this) {
                successfulCrossovers++;
            }
        }
        public void increaseFailedCrossovers() {
            synchronized (Result.this) {
                failedCrossovers++;
            }
        }
        public void increaseLlmCallsOnUnmodified() {
            synchronized (Result.this) {
                llmCallsOnUnmodified++;
            }
        }
        public void increaseLlmCallsOnMutated() {
            synchronized (Result.this) {
                llmCallsOnMutated++;
            }
        }
    }
    private MutationStats mutationStats;
    
    public class LlmStats {
        private int calls;
        private int answers;
        private int unusableAnswers;
        private int timeouts;
        private long totalQueryTokens;
        private long totalAnswerTokens;
        public void increaseCalls() {
            synchronized (Result.this) {
                calls++;
            }
        }
        public void increaseAnswers() {
            synchronized (Result.this) {
                answers++;
            }
        }
        public void increaseUnusableAnswers() {
            synchronized (Result.this) {
                unusableAnswers++;
            }
        }
        public void increaseTimeouts() {
            synchronized (Result.this) {
                timeouts++;
            }
        }
        public void increaseTotalQueryTokens(long amount) {
            synchronized (Result.this) {
                totalQueryTokens += amount;
            }
        }
        public void increaseTotalAnswerTokens(long amount) {
            synchronized (Result.this) {
                totalAnswerTokens += amount;
            }
        }
    }
    private LlmStats llmStats;
    
    private List<String> failingTests; // set only by SetupTest
    
    public class QueryStats {
        private Integer lines;
        private Integer tokens;
        private Integer humanPatchHunksInQuery;
        private Integer humanPatchHunksNotInQuery;
        public record Snippets(
                int relevant,
                int irrelevant,
                int relevantLines,
                int irrelevantLines,
                int relevantTokens,
                int irrelevantTokens) {
        }
        private Snippets snippets;
        
        public void setLines(int lines) {
            synchronized (Result.this) {
                this.lines = lines;
            }
        }
        public void setTokens(int tokens) {
            synchronized (Result.this) {
                this.tokens = tokens;
            }
        }
        public void setHumanPatchHunksInQuery(int humanPatchHunksInQuery) {
            synchronized (Result.this) {
                this.humanPatchHunksInQuery = humanPatchHunksInQuery;
            }
        }
        public void setHumanPatchHunksNotInQuery(int humanPatchHunksNotInQuery) {
            synchronized (Result.this) {
                this.humanPatchHunksNotInQuery = humanPatchHunksNotInQuery;
            }
        }
        public void setSnippets(Snippets snippets) {
            synchronized (Result.this) {
                this.snippets =  snippets;
            }
        }
    }
    private QueryStats query; // set only by LlmQueryAnalysis
    
    private Map<String, Long> timings = new LinkedHashMap<>();
    private Map<String, Integer> logStats = new LinkedHashMap<>();
    
    
    public synchronized void setResult(String result) {
        this.result = result;
    }
    
    public synchronized void setGeneration(Integer generation) {
        this.generation = generation;
    }
    
    public synchronized void setException(String exception) {
        this.exception = exception;
    }
    
    public synchronized Fitness fitness() {
        if (fitness == null) {
            fitness = new Fitness();
        }
        return fitness;
    }
    
    public synchronized Patch patch() {
        if (patch == null) {
            patch = new Patch();
        }
        return patch;
    }

    public synchronized AstStats astStats() {
        if (ast == null) {
            ast = new AstStats();
        }
        return ast;
    }
    
    public synchronized EvaluationStats evaluationStats() {
        if (evaluations == null) {
            evaluations = new EvaluationStats();
        }
        return evaluations;
    }
    
    public synchronized MutationStats mutationStats() {
        if (mutationStats == null) {
            mutationStats = new MutationStats();
        }
        return mutationStats;
    }
    
    public synchronized LlmStats llmStats() {
        if (llmStats == null) {
            llmStats = new LlmStats();
        }
        return llmStats;
    }
    
    public synchronized void setFailingTests(List<String> failingTests) {
        this.failingTests = List.copyOf(failingTests);
    }
    
    public synchronized QueryStats queryStats() {
        if (query == null) {
            query = new QueryStats();
        }
        return query;
    }
    
    public synchronized void putTiming(String probe, long measurement) {
        timings.put(probe, measurement);
    }
    
    public synchronized void putLogCount(String level, int number) {
        logStats.put(level, number);
    }
    
}
