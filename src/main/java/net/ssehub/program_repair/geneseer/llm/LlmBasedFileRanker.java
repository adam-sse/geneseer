package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gson.JsonParseException;

import net.ssehub.program_repair.geneseer.code.LeafNode;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.llm.CodeSnippet.LineRange;
import net.ssehub.program_repair.geneseer.util.JsonUtils;

public class LlmBasedFileRanker implements ISnippetRanker {
    
    private static final Logger LOG = Logger.getLogger(LlmBasedFileRanker.class.getName());

    private static final Map<String, ?> JSON_SCHEMA = JsonUtils.parseToMap("""
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "AgentFileRankingResponse",
              "type": "object",
              "required": ["candidate_files"],
              "additionalProperties": false,
              "properties": {
                "candidate_files": {
                  "type": "array",
                  "maxItems": 10,
                  "items": {
                    "type": "object",
                    "required": ["path", "reasoning", "confidence"],
                    "additionalProperties": false,
                    "properties": {
                      "path": {
                        "type": "string",
                        "pattern": "^[a-zA-Z0-9_./-]+$",
                        "description": "Relative path from workspace root. Must exist in file inventory."
                      },
                      "reasoning": {
                        "type": "string",
                        "maxLength": 200,
                        "description": "One to two sentence justification for inclusion."
                      },
                      "confidence": {
                        "type": "number",
                        "minimum": 0.0,
                        "maximum": 1.0,
                        "description": "Agent's confidence that this file is relevant. 0.0 = uncertain, 1.0 = certain."
                      }
                    }
                  }
                }
              }
            }
            """);
    
    private static final String SYSTEM_PROMPT = """
            You are a file ranking assistant for a Java automated program repair system.
            
            Your task: given a specification and a project file inventory, identify which
            files are most likely to need reading or modification to implement the spec.
            
            Rules:
            - You MUST only suggest files that exist in the provided inventory.
            - Rank files by relevance to the specification.
            - Provide a short reasoning for each file.
            - Assign a confidence score (0.0 to 1.0) per file.
            - Return at most 10 files.
            - Focus on production source files that contain or should contain the target functionality.
            
            Output format: respond with a single JSON object matching the schema below.
            Do not include any text outside the JSON object.
            """;
    
    private ILlm llm;
    
    public LlmBasedFileRanker(ILlm llm) {
        this.llm = llm;
    }
    
    @Override
    public List<CodeSnippet> selectCodeSnippets(Node code, List<TestMethodContext> failingTestMethods)
            throws IOException {
        Query query = createQuery(code, failingTestMethods);
        IResponse response = llm.send(query);
        LOG.fine(() -> "File context selection response: " + response.getContent());

        List<CodeSnippet> result = new LinkedList<>();
        try {
            Map<String, Object> json = JsonUtils.parseToMap(response.getContent());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) json.get("candidate_files");
            for (Map<String, Object> file : files) {
                Path path = Path.of((String) file.get("path"));
                Optional<Node> match = code.stream()
                        .filter(n -> n.getMetadata(Metadata.FILE_NAME) != null)
                        .filter(n -> n.getMetadata(Metadata.FILE_NAME).equals(path))
                        .findAny();
                if (match.isPresent()) {
                    Node fileNode = match.get();
                    CodeSnippet snippet = new CodeSnippet(path, LineRange.getRange(code, fileNode),
                            Arrays.asList(fileNode.getTextFormatted().split("\n")));
                    result.add(snippet);
                    
                } else {
                    LOG.warning(() -> "Could not find file " + path + " from LLM answer");
                }
            }
            
        } catch (JsonParseException | ClassCastException | NullPointerException e) {
            throw new IOException("Invalid JSON response from LLM", e);
        }
        
        LOG.info(() -> result.size() + " files selected by LLM: " + result.stream().map(CodeSnippet::getFile).toList());
        
        return result;
    }
    
    private Query createQuery(Node code, List<TestMethodContext> failingTestMethods) {
        Query query = new Query();
        query.addMessage(new Message(Role.SYSTEM, SYSTEM_PROMPT));
        query.addMessage(new Message(Role.USER, createPrompt(code, failingTestMethods)));
        query.setJsonSchema(JSON_SCHEMA);
        return query;
    }
    
    private String createPrompt(Node code, List<TestMethodContext> failingTestMethods) {
        List<Node> files = code.stream()
                .filter(n -> n.getMetadata(Metadata.FILE_NAME) != null)
                .toList();
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("## FAILING TESTS\n");
        LlmFixer.writeFailingTestCases(prompt, failingTestMethods);
        
        prompt.append("## PROJECT FILE INVENTORY\n");
        files.stream()
                .map(n -> (Path) n.getMetadata(Metadata.FILE_NAME))
                .forEach(p -> prompt.append(p).append('\n'));
        prompt.append("\n");
        
        prompt.append("## SYMBOL SIGNATURES (top-level functions and classes)\n");
        prompt.append(LlmFixer.createProjectOutline(code, null));
        
        prompt.append("\n\n");
        prompt.append("## IMPORT GRAPH (file → imports)\n");
        Set<String> projectPackages = new HashSet<>();
        for (Node file : files) {
            if (file.childCount() > 0 && file.get(0).childCount() > 0
                    && file.get(0).get(0) instanceof LeafNode leaf && leaf.getText().equals("package")) {
                projectPackages.add(file.get(0).getTextSingleLine().replace("package ", "").replace(";", ""));
            }
        }
        
        Map<Path, List<String>> imports = new LinkedHashMap<>(files.size());
        for (Node file : files) {
            imports.put((Path) file.getMetadata(Metadata.FILE_NAME), file.stream()
                    .filter(n -> n.childCount() > 0 && n.get(0).getTextFormatted().equals("import"))
                    .map(n -> n.getTextSingleLine().replace("import ", "").replace(";", ""))
                    .filter(i -> !i.startsWith("java."))
                    .filter(i -> {
                        boolean anyMatch = false;
                        for (String projectPackage : projectPackages) {
                            if (i.startsWith(projectPackage)) {
                                anyMatch = true;
                                break;
                            }
                        }
                        return anyMatch;
                    })
                    .toList());
        }
        imports.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(e -> e.getKey() +  " → " + e.getValue() + "\n")
                .forEach(prompt::append);
        
        prompt.append("\n");
        prompt.append("""
                ## TASK
                Identify the files most relevant to fixing the failing tests above.
                Consider:
                1. Files that likely contain the class/method mentioned in the test(s)
                2. Files that import or are imported by likely target files
                3. Utility or helper files that the implementation may depend on
                4. Configuration files only if the spec involves configuration changes
                
                Respond with JSON only.
                """);
        
        String result = prompt.toString();
        LOG.fine(() -> "File context selection prompt:\n" + result);
        return result;
    }

    @Override
    public boolean needsTestMethodContext() {
        return true;
    }

}
