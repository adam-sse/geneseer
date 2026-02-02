package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.ChangedArea;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.util.AstUtils;

public class LlmQueryAnalysis implements IFixer {

    private static final Logger LOG = Logger.getLogger(LlmQueryAnalysis.class.getName());
    
    private Path projectRoot;
    
    public LlmQueryAnalysis(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public Node run(Node original, TestSuite testSuite, Map<String, Object> result) throws IOException {
        List<CodeSnippet> codeSnippets = selectMostSuspiciousMethods(original);
        
        Path changedAreasFile = projectRoot.resolve("geneseer-changed-areas.json");
        java.lang.reflect.Type listType = new TypeToken<List<ChangedArea>>() {
        }.getType();
        List<ChangedArea> changedByHumanPatch = new Gson().fromJson(
                Files.readString(changedAreasFile, StandardCharsets.UTF_8), listType);
        
        List<ChangedArea> in = new LinkedList<>();
        List<ChangedArea> out = new LinkedList<>();
        Set<CodeSnippet> irrelevant = new HashSet<>();
        irrelevant.addAll(codeSnippets);
        
        for (ChangedArea area : changedByHumanPatch) {
            boolean containedByAny = false;
            for (CodeSnippet snippet : codeSnippets) {
                if (snippet.contains(area)) {
                    in.add(area);
                    irrelevant.remove(snippet);
                    containedByAny = true;
                }
            }
            
            if (!containedByAny) {
                out.add(area);
            }
        }
        
        LOG.info(() -> "Contained in query: " + in);
        LOG.info(() -> "Not contained in query: " + out);
        LOG.info(() -> "Irrelevant query parts: " + irrelevant);
        result.put("patchParts", Map.of("inQuery", in.size(), "notInQuery", out.size()));
        if (out.size() == 0) {
            result.put("result", "GOOD");
        } else if (in.size() == 0) {
            result.put("result", "BAD");
        } else {
            result.put("result", "MIXED");
        }
        
        int relevantLines = codeSnippets.stream()
                .filter(s -> !irrelevant.contains(s))
                .mapToInt(s -> s.lineRange.size()).sum();
        int irrelevantLines = irrelevant.stream().mapToInt(s -> s.lineRange.size()).sum();
        result.put("queryLines", Map.of("relevant", relevantLines, "irrelevant", irrelevantLines));
        
        return null;
    }

    private List<CodeSnippet> selectMostSuspiciousMethods(Node original) throws IOException {
        LinkedHashMap<Node, Double> methodSuspiciousness = getSuspiciousnessByMethod(original);
        List<CodeSnippet> selectedMethods = new LinkedList<>();
        int codeSize = 0;
        for (Map.Entry<Node, Double> entry : methodSuspiciousness.entrySet()) {
            Node method = entry.getKey();
            LineRange range = getRange(original, method);
            
            if (selectedMethods.isEmpty() || codeSize + range.size() < Configuration.INSTANCE.llm().maxCodeContext()) {
                selectedMethods.add(getSnippetForMethod(original, method));
                codeSize += range.size();
            }
        }
        return selectedMethods;
    }

    private LinkedHashMap<Node, Double> getSuspiciousnessByMethod(Node original) {
        List<Node> methods = original.stream()
                .filter(n -> n.getType() == Type.METHOD || n.getType() == Type.CONSTRUCTOR)
                .toList();
        Map<Node, Double> methodSuspiciousness = new HashMap<>(methods.size());
        for (Node method : methods) {
            double suspiciousnessMax = method.stream()
                    .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                    .mapToDouble(n -> (double) n.getMetadata(Metadata.SUSPICIOUSNESS))
                    .max().orElse(0.0);
            if (suspiciousnessMax > 0) {
                methodSuspiciousness.put(method, suspiciousnessMax);
            }
        }
        
        LinkedHashMap<Node, Double> sortedSuspiciousness = new LinkedHashMap<>(methodSuspiciousness.size());
        methodSuspiciousness.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .forEach(e -> sortedSuspiciousness.put(e.getKey(), e.getValue()));
        return sortedSuspiciousness;
    }

    private class CodeSnippet {
        private Path file;
        private LineRange lineRange;
        
        public CodeSnippet(Path file, LineRange lineRange) {
            this.file = file;
            this.lineRange = lineRange;
        }

        public boolean contains(ChangedArea changedArea) {
            return file.equals(Path.of(changedArea.file())) && lineRange.start() <= changedArea.start()
                    && lineRange.end() >= changedArea.end();
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("CodeSnippet [file=");
            builder.append(file);
            builder.append(", lineRange=");
            builder.append(lineRange);
            builder.append("]");
            return builder.toString();
        }
        
    }

    private CodeSnippet getSnippetForMethod(Node root, Node method) throws IOException {
        Path filename = null;
        List<Node> parents = root.getPath(method);
        for (Node parent : parents) {
            if (parent.getType() == Type.COMPILATION_UNIT) {
                filename = (Path) parent.getMetadata(Metadata.FILE_NAME);
                break;
            }
        }
        
        LineRange range = getRange(root, method);

        return new CodeSnippet(filename, range);
    }

    protected record LineRange(int start, int end) {
        public int size() {
            return end - start + 1;
        }
        
    }
    private LineRange getRange(Node root, Node node) {
        int start = AstUtils.getLine(root, node);
        int end = start + AstUtils.getAdditionalLineCount(node);
        return new LineRange(start, end);
    }

}
