package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.llm.ChatGptMessage.Role;
import net.ssehub.program_repair.geneseer.parsing.Parser;
import net.ssehub.program_repair.geneseer.parsing.Writer;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.parsing.model.Position;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class LlmFixer {

    private static final Logger LOG = Logger.getLogger(LlmFixer.class.getName());
    
    private static final String SYSTEM_MESSAGE = "You are an automated program repair tool. Write no explanations and"
            + " only output the fixed code. Output the complete (fixed) code that is given to you, even if only a small"
            + " part of it is changed.";
    
    private IChatGptConnection llm;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Charset encoding;
    
    private Path projectRoot;

    public LlmFixer(IChatGptConnection llm, TemporaryDirectoryManager tempDirManager, Charset encoding,
            Path projectRoot) {
        this.llm = llm;
        this.tempDirManager = tempDirManager;
        this.encoding = encoding;
        this.projectRoot = projectRoot;
    }

    public Optional<Node> createVariant(Node original, List<TestResult> failingTests) throws IOException {
        Path sourceDir = tempDirManager.createTemporaryDirectory();
        Writer.write(original, null, sourceDir, encoding);
        
        List<CodeSnippet> codeSnippets = selectMostSuspiciousMethods(original, sourceDir);
        
        String answer = query(failingTests.stream().map(TestResult::failureMessage).toList(),
                failingTests.stream().map(this::getTestMethodContext).toList(),
                codeSnippets);
        
        LOG.fine(() -> "Got answer:\n" + answer);
        
        
        Optional<Node> result;
        try {
            parseAnswerSnippets(answer, codeSnippets);
            
            Map<Path, List<CodeSnippet>> modifiedSnippetsByFile = new HashMap<>(codeSnippets.size());
            for (CodeSnippet snippet : codeSnippets) {
                if (snippet.newLines != null) {
                    List<CodeSnippet> snippetsInFile = modifiedSnippetsByFile.getOrDefault(
                            snippet.file, new LinkedList<>());
                    snippetsInFile.add(snippet);
                    modifiedSnippetsByFile.put(snippet.file, snippetsInFile);
                }
            }
            
            LOG.fine(() -> "Answer has modified " + codeSnippets.stream().filter(s -> s.newLines != null).count()
                    + " snippets in " + modifiedSnippetsByFile.size() + " files");
            for (Map.Entry<Path, List<CodeSnippet>> entry : modifiedSnippetsByFile.entrySet()) {
                writeModifiedFile(entry.getKey(), entry.getValue());
            }

            Node variant = Parser.parse(sourceDir, encoding);
            result = Optional.of(variant);
            
            try {
                String astDiff = AstDiff.getDiff(original, variant, tempDirManager, encoding);
                LOG.info(() -> "Diff of created variant:\n" + astDiff);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to create diff of variant", e);
            }
                
        } catch (AnswerDoesNotApplyException e) {
            LOG.log(Level.WARNING, "Answer cannot be applied to variant", e);
            result = Optional.empty();
        }
        tempDirManager.deleteTemporaryDirectory(sourceDir);
        
        return result;
    }

    private List<CodeSnippet> selectMostSuspiciousMethods(Node original, Path sourceDir) throws IOException {
        LinkedHashMap<Node, Double> methodSuspiciousness = getSuspiciousnessByMethod(original);
        List<CodeSnippet> selectedMethods = new LinkedList<>();
        int codeSize = 0;
        for (Map.Entry<Node, Double> entry : methodSuspiciousness.entrySet()) {
            Node method = entry.getKey();
            LineRange range = getRange(method);
            
            if (selectedMethods.isEmpty() || codeSize + range.size() < Configuration.INSTANCE.llm().maxCodeContext()) {
                selectedMethods.add(getSnippetForMethod(original, method, sourceDir));
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
        private List<String> lines;
        private List<String> newLines;
        
        public CodeSnippet(Path file, LineRange lineRange, List<String> lines) {
            this.file = file;
            this.lineRange = lineRange;
            this.lines = lines;
        }
    }

    private CodeSnippet getSnippetForMethod(Node root, Node method, Path fileOrSourceDir) throws IOException {
        Path filename = null;
        List<Node> parents = root.getPath(method);
        for (Node parent : parents) {
            if (parent.getType() == Type.COMPILATION_UNIT) {
                filename = (Path) parent.getMetadata(Metadata.FILE_NAME);
                break;
            }
        }
        
        LineRange range = getRange(method);

        Path file = fileOrSourceDir;
        if (!Files.isRegularFile(fileOrSourceDir)) {
            file = fileOrSourceDir.resolve(filename);
        }
        List<String> lines = Files.readAllLines(file, encoding);
        
        return new CodeSnippet(file, range, lines.subList(range.start() - 1, range.end()));
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

    private List<String> getTestMethodContext(TestResult failingTest) {
        String testFileName = failingTest.testClass().substring(failingTest.testClass().lastIndexOf('.') + 1) + ".java";
        
        List<String> result = null;
        try {
            List<Path> testFiles = Files.walk(projectRoot)
                    .filter(p -> p.getFileName().toString().equals(testFileName))
                    .filter(p -> Files.isRegularFile(p))
                    .toList();
            
            for (Path testFile : testFiles) {
                result = findTestMethodInFile(failingTest, testFile);
            }
            
            if (result == null) {
                if (testFiles.isEmpty()) {
                    LOG.warning(() -> "Could not find test file for test class " + failingTest.testClass());
                } else {
                    LOG.warning(() -> "Could not find test method " + failingTest.testMethod() + " in any of these"
                            + " test files: " + testFiles);
                }
            }
                
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read test method file", e);
        }
        return result;
    }

    private List<String> findTestMethodInFile(TestResult failingTest, Path testFile)
            throws IOException {
        
        Node file = Parser.parseSingleFile(testFile, encoding);
        Optional<Node> method = file.stream()
                .filter(n -> n.getType() == Type.METHOD)
                .filter(n -> n.getMetadata(Metadata.METHOD_NAME).equals(failingTest.testMethod()))
                .findAny();
        
        List<String> result = null;
        if (method.isPresent()) {
            CodeSnippet snippet = getSnippetForMethod(file, method.get(), testFile);
            result = snippet.lines;
        }
        
        return result;
    }

    private String query(List<String> failureMessages, List<List<String>> testMethodContexts,
            List<CodeSnippet> codeSnippets) throws IOException {
        ChatGptRequest request = new ChatGptRequest(Configuration.INSTANCE.llm().model());
        request.addMessage(new ChatGptMessage(SYSTEM_MESSAGE, Role.SYSTEM));
        
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < failureMessages.size(); i++) {
            if (testMethodContexts.get(i) != null) {
                query.append("Test code");
                if (failureMessages.size() > 1) {
                    query.append(' ').append(i + 1);
                }
                query.append(":\n```java\n");
                for (String line : testMethodContexts.get(i)) {
                    query.append(line).append('\n');
                }
                query.append("```\n");
            }
            query.append("Failure message");
            if (failureMessages.size() > 1) {
                query.append(' ').append(i + 1);
            }
            query.append(":\n```\n").append(failureMessages.get(i)).append("\n```\n\n");
        }
        
        query.append("Here are " + codeSnippets.size() + " code snippets that may need to be fixed:");
        int index = 1;
        for (CodeSnippet snippet : codeSnippets) {
            query.append("\n\nCode snippet number " + (index++) + ":\n```java\n");
            snippet.lines.stream().forEach(line -> query.append(line).append('\n'));
            query.append("```");
        }
        query.append("\n\nYou must prefix your fixed code with \"Code snippet number <number>\" to clarify which "
                + "code snippet you modified (you may modify multiple code snippets). Do not output code snippets"
                + " that you did not modify.");
        
        LOG.fine(() -> "Query:\n" + query);
        request.addMessage(new ChatGptMessage(query.toString(), Role.USER));
        if (Configuration.INSTANCE.llm().temperature() != null) {
            request.setTemperature(Configuration.INSTANCE.llm().temperature());
        }
        if (Configuration.INSTANCE.llm().seed() != null) {
            request.setSeed(Configuration.INSTANCE.llm().seed());
        }
        
        ChatGptResponse response = llm.send(request);
        return response.getContent();
    }
    
    private void parseAnswerSnippets(String answer, List<CodeSnippet> snippets) throws AnswerDoesNotApplyException {
        Pattern pattern = Pattern.compile("Code snippet number (?<number>\\d+)", Pattern.CASE_INSENSITIVE);
        
        String[] answerLines = answer.split("\n");
        for (int i = 0; i < answerLines.length; i++) {
            if (answerLines[i].isEmpty()) {
                continue;
            }
            Matcher m = pattern.matcher(answerLines[i]);
            if (!m.find()) {
                boolean fixable = false;
                if (answerLines[i].startsWith("```") && i + 1 < answerLines.length) {
                    m = pattern.matcher(answerLines[i + 1]);
                    if (m.find()) {
                        fixable = true;
                        String tmp = answerLines[i];
                        answerLines[i] = answerLines[i + 1];
                        answerLines[i + 1] = tmp;
                    }
                }
                
                if (!fixable) {
                    throw new AnswerDoesNotApplyException("missing proper code snippet heading in line: "
                            + answerLines[i]);
                }
            }
            int snippetNumber = Integer.parseInt(m.group("number"));
            if (snippetNumber <= 0 || snippetNumber > snippets.size()) {
                throw new AnswerDoesNotApplyException("invalid snippet number " + snippetNumber);
            }
            
            i++;
            if (i >= answerLines.length || !answerLines[i].startsWith("```")) {
                throw new AnswerDoesNotApplyException("missing starting code block for snippet " + snippetNumber);
            }
            i++;
            
            List<String> lines = new LinkedList<>();
            for (; i < answerLines.length; i++) {
                if (answerLines[i].startsWith("```")) {
                    break;
                }
                lines.add(answerLines[i]);
            }
            
            snippets.get(snippetNumber - 1).newLines = lines;
        }
    }
    
    private void writeModifiedFile(Path file, List<CodeSnippet> modifiedCodeSnippets) throws IOException {
        List<String> oldLines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> newLines = new LinkedList<>();

        for (int i = 0; i < oldLines.size(); i++) {
            int lineNumber = i + 1;
            boolean wasInAnySnippet = false;
            for (CodeSnippet snippet : modifiedCodeSnippets) {
                if (lineNumber == snippet.lineRange.start()) {
                    wasInAnySnippet = true;
                    newLines.addAll(snippet.newLines);
                    i = snippet.lineRange.end() - 1;
                }
            }
            
            if (!wasInAnySnippet) {
                newLines.add(oldLines.get(i));
            }
        }
        
        Files.writeString(file, newLines.stream().collect(Collectors.joining("\n")), encoding);
    }
    
}
