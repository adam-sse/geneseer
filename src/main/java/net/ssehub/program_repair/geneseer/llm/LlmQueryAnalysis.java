package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.charset.Charset;
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

import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.parsing.model.Position;

public class LlmQueryAnalysis {

    private static final Logger LOG = Logger.getLogger(LlmQueryAnalysis.class.getName());
    
    private Charset encoding;
    
    private Path projectRoot;
    
    public LlmQueryAnalysis(Charset encoding, Path projectRoot) {
        this.encoding = encoding;
        this.projectRoot = projectRoot;
    }

    public String analyzeQueryForProject(Node original, List<TestResult> failingTests) throws IOException {
        List<CodeSnippet> codeSnippets = selectMostSuspiciousMethods(original);
        
        Path changedAreasFile = projectRoot.resolve("geneseer-changed-areas.json");
        java.lang.reflect.Type listType = new TypeToken<List<ChangedArea>>() {
        }.getType();
        List<ChangedArea> changedByHumanPatch = new Gson().fromJson(
                Files.readString(changedAreasFile, encoding), listType);
        
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
        
        int relevantLines = codeSnippets.stream()
                .filter(s -> !irrelevant.contains(s))
                .mapToInt(s -> s.lineRange.size()).sum();
        int irrelevantLines = irrelevant.stream().mapToInt(s -> s.lineRange.size()).sum();
        
        String result = in.size() + ";" + out.size() + ";" + irrelevant.size()
                + ";" + relevantLines + ";" + irrelevantLines;
        return result;
    }

    private List<CodeSnippet> selectMostSuspiciousMethods(Node original) throws IOException {
        LinkedHashMap<Node, Double> methodSuspiciousness = getSuspiciousnessByMethod(original);
        List<CodeSnippet> selectedMethods = new LinkedList<>();
        int codeSize = 0;
        for (Map.Entry<Node, Double> entry : methodSuspiciousness.entrySet()) {
            Node method = entry.getKey();
            LineRange range = getRange(method);
            
            if (selectedMethods.isEmpty() || codeSize + range.size() < LlmConfiguration.INSTANCE.getMaxCodeContext()) {
                selectedMethods.add(getSnippetForMethod(original, method));
                codeSize += range.size();
            }
        }
        return selectedMethods;
    }

    private LinkedHashMap<Node, Double> getSuspiciousnessByMethod(Node original) {
        List<Node> methods = original.stream().filter(n -> n.getType() == Type.METHOD).toList();
        Map<Node, Double> methodSuspiciousness = new HashMap<>(methods.size());
        for (Node method : methods) {
            double suspiciousnessSum = method.stream()
                    .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                    .mapToDouble(n -> (double) n.getMetadata(Metadata.SUSPICIOUSNESS))
                    .max().orElse(0.0);
            if (suspiciousnessSum > 0) {
                methodSuspiciousness.put(method, suspiciousnessSum);
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
                    && lineRange.end() >= changedArea.start() + changedArea.size();
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
        
        LineRange range = getRange(method);

        return new CodeSnippet(filename, range);
    }

    protected record LineRange(int start, int end) {
        public int size() {
            return end - start + 1;
        }
        
    }
    private LineRange getRange(Node node) {
        Node firstLeaf = node;
        while (firstLeaf.getType() != Type.LEAF) {
            firstLeaf = firstLeaf.get(0);
        }
        Node lastLeaf = node;
        while (lastLeaf.getType() != Type.LEAF) {
            lastLeaf = lastLeaf.get(lastLeaf.childCount() - 1);
        }
        
        Position start = ((LeafNode) firstLeaf).getOriginalPosition();
        Position end = ((LeafNode) lastLeaf).getOriginalPosition();
        
        return new LineRange(start.line(), end.line());
    }

}
