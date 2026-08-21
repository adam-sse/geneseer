package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.llm.CodeSnippet.LineRange;
import net.ssehub.program_repair.geneseer.llm.openai.OpenaiSavedAnswers;
import net.ssehub.program_repair.geneseer.util.JsonUtils;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class SavedLlmMutator extends AbstractLlmMutator {

    private OpenaiSavedAnswers savedLlm;
    
    public SavedLlmMutator(OpenaiSavedAnswers savedLlm, TemporaryDirectoryManager tempDirManager, Charset encoding,
            Path projectRoot) {
        super(tempDirManager, encoding, projectRoot);
        this.savedLlm = savedLlm;
    }
    
    @Override
    public String getName() {
        return savedLlm.getName();
    }
    
    @Override
    public boolean needsFaultLocalization() {
        return false;
    }

    @Override
    public List<CodeSnippet> selectMostSuspiciousMethods(Node original, List<TestResult> failingTests)
            throws IOException {
        Path jsonFile = savedLlm.getNextAnswerFile();
        Map<String, ?> json = JsonUtils.parseToMap(Files.readString(jsonFile, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        List<Map<String, ?>> jsonSnippets = (List<Map<String, ?>>) json.get("codeSnippets");
        if (jsonSnippets == null) {
            throw new IOException("Missing JSON field codeSnippets in file " + jsonFile);
        }
        
        List<CodeSnippet> codeSnippets = new LinkedList<>();
        for (Map<String, ?> jsonSnippet : jsonSnippets) {
            codeSnippets.add(new CodeSnippet(Path.of((String) jsonSnippet.get("file")),
                    new LineRange(getLineNumber(jsonSnippet, "lineStart", jsonFile),
                            getLineNumber(jsonSnippet, "lineEnd", jsonFile)),
                    Collections.emptyList()));
        }
        
        return codeSnippets;
    }
    
    private static int getLineNumber(Map<String, ?> jsonSnippet, String fieldName, Path jsonFile) throws IOException {
        Object obj = jsonSnippet.get(fieldName);
        if (obj == null) {
            throw new IOException("Missing JSON field " + fieldName + " in file " + jsonFile);
        }
        if (obj instanceof Number num) {
            return num.intValue();
        } else {
            throw new IOException("JSON field " + fieldName + " is not a number in file " + jsonFile);
        }
    }

    @Override
    protected String runQuery(Query query) throws IOException {
        getLlmStats().increaseCalls();
        IResponse response = savedLlm.send(query);
        getLlmStats().increaseAnswers();
        getLlmStats().increaseTotalQueryTokens(response.getQueryTokens());
        getLlmStats().increaseTotalAnswerTokens(response.getAnswerTokens());
        return response.getContent();
    }

}
