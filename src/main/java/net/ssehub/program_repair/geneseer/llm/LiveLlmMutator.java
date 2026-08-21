package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class LiveLlmMutator extends AbstractLlmMutator {

    private static final Logger LOG = Logger.getLogger(LiveLlmMutator.class.getName());
    
    private ILlm llm;
    
    private ISnippetRanker ranker;
    
    public LiveLlmMutator(ILlm llm, ISnippetRanker ranker, TemporaryDirectoryManager tempDirManager, Charset encoding,
            Path projectRoot) {
        super(tempDirManager, encoding, projectRoot);
        this.llm = llm;
        this.ranker = ranker;
    }
    
    @Override
    public String getName() {
        return llm.getName();
    }
    
    @Override
    public boolean needsFaultLocalization() {
        return true;
    }
    
    @Override
    public List<CodeSnippet> selectMostSuspiciousMethods(Node original, List<TestResult> failingTests)
            throws IOException {
        
        List<CodeSnippet> selected = ranker.selectCodeSnippets(original,
                TestMethodContext.constructContext(failingTests, getProjectRoot(), getEncoding()));
        return selected;
    }
    
    @Override
    protected String runQuery(Query query) throws IOException {
        try (Measurement.Probe m = Measurement.INSTANCE.start("llm-query")) {
            LOG.info("Sending query to LLM: " + query);
            getLlmStats().increaseCalls();
            IResponse response = llm.send(query);
            if (response.getThinking() != null) {
                LOG.fine(() -> "Got " + response.getThinking().length() + " characters of thinking trace");
            }
            getLlmStats().increaseAnswers();
            getLlmStats().increaseTotalQueryTokens(response.getQueryTokens());
            getLlmStats().increaseTotalAnswerTokens(response.getAnswerTokens());
            return response.getContent();
        } catch (HttpTimeoutException e) {
            getLlmStats().increaseTimeouts();
            throw e;
        }
    }
    
}
