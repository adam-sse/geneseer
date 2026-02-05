package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.LeafNode;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Parser;
import net.ssehub.program_repair.geneseer.code.Writer;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.llm.ChatGptMessage.Role;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class LlmFixer {

    private static final Logger LOG = Logger.getLogger(LlmFixer.class.getName());
    
    private static final String SYSTEM_MESSAGE = "You are an automated program repair tool for Java programs.";
    
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
        List<CodeSnippet> codeSnippets = selectMostSuspiciousMethods(original);
        ChatGptRequest query = createQuery(original, failingTests, codeSnippets);
        String answer = runQuery(query);
        LOG.fine(() -> "Got answer:\n" + answer);
        
        Node variant = original.clone();
        Path sourceDir = null;
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
            
            LOG.info(() -> "LLM answer has modified " + codeSnippets.stream().filter(s -> s.newLines != null).count()
                    + " snippets in " + modifiedSnippetsByFile.size() + " files");
            
            sourceDir = tempDirManager.createTemporaryDirectory();
            Writer.write(original, sourceDir, encoding);
            Parser parser = new Parser();
            for (Map.Entry<Path, List<CodeSnippet>> entry : modifiedSnippetsByFile.entrySet()) {
                Path absolutePath = sourceDir.resolve(entry.getKey());
                writeModifiedFile(absolutePath, entry.getValue());
                
                Node originalFileNode = null;
                int originalIndex = -1;
                for (int i = 0; i < variant.childCount(); i++) {
                    if (variant.get(i).getMetadata(Metadata.FILE_NAME).equals(entry.getKey())) {
                        originalIndex = i;
                        originalFileNode = variant.get(originalIndex);
                    }
                }
                if (originalIndex == -1) {
                    throw new AnswerDoesNotApplyException("Can't find modified file " + entry.getKey() + " in AST");
                }
                
                Node modifiedFileNode = parser.parseSingleFile(absolutePath, encoding);
                modifiedFileNode.copyMetadataFromNode(originalFileNode);
                variant.set(originalIndex, modifiedFileNode);
            }

            try {
                String astDiff = AstDiff.getDiff(original, variant, tempDirManager, encoding);
                LOG.info(() -> "Diff of created variant:\n" + astDiff);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to create diff of variant", e);
            }
                
        } catch (AnswerDoesNotApplyException e) {
            LOG.log(Level.WARNING, "Answer cannot be applied to variant", e);
            variant = null;
        } finally {
            if (sourceDir != null) {
                tempDirManager.deleteTemporaryDirectory(sourceDir);
            }
        }
        
        return Optional.ofNullable(variant);
    }

    public ChatGptRequest createQuery(Node code, List<TestResult> failingTests, List<CodeSnippet> codeSnippets) {
        List<String> failureMessages = failingTests.stream().map(TestResult::failureMessage).toList();
        List<TestMethodContext> testMethodContext = failingTests.stream().map(this::getTestMethodContext).toList();
        String projectOutline = createProjectOutline(code);
        
        ChatGptRequest request = new ChatGptRequest(Configuration.INSTANCE.llm().model());
        request.addMessage(new ChatGptMessage(SYSTEM_MESSAGE, Role.SYSTEM));
        
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < failureMessages.size(); i++) {
            if (testMethodContext.get(i) != null) {
                TestMethodContext context = testMethodContext.get(i);
                query.append("Failing test method");
                if (failureMessages.size() > 1) {
                    query.append(' ').append(i + 1);
                }
                if (context.testClassName() != null) {
                    query.append(" in test class ").append(context.testClassName());
                }
                query.append(":\n```java\n");
                query.append(context.code);
                query.append("\n```\n");
            }
            query.append("Failure message");
            if (failureMessages.size() > 1) {
                query.append(' ').append(i + 1);
            }
            query.append(":\n```\n").append(failureMessages.get(i)).append("\n```\n\n");
        }
        
        if (projectOutline != null) {
            query.append("Here is an overview of the whole project:\n\n");
            query.append(projectOutline);
            query.append("\n");
        }
        
        query.append("Here are " + codeSnippets.size() + " code snippets that may need to be fixed:");
        int index = 1;
        for (CodeSnippet snippet : codeSnippets) {
            query.append("\n\n");
            query.append("File ").append(snippet.file).append('\n');
            query.append("Code snippet number ").append(index++).append(":\n```java\n");
            snippet.lines.stream().forEach(line -> query.append(line).append('\n'));
            query.append("```");
        }
        query.append("\n\nYou must prefix your fixed code with \"Code snippet number <number>\" to clarify which"
                + " code snippet you modified (you may modify multiple code snippets). Do not output code snippets"
                + " that you did not modify. Surround each code snippet with ``` markers (after the code snippet"
                + " number). Output the complete fixed code that is given to you, even if only a small part of it"
                + " is changed.");
        
        LOG.fine(() -> "Query:\n" + query);
        request.addMessage(new ChatGptMessage(query.toString(), Role.USER));
        if (Configuration.INSTANCE.llm().temperature() != null) {
            request.setTemperature(Configuration.INSTANCE.llm().temperature());
        }
        if (Configuration.INSTANCE.llm().seed() != null) {
            request.setSeed(Configuration.INSTANCE.llm().seed());
        }
        return request;
    }

    public List<CodeSnippet> selectMostSuspiciousMethods(Node original) {
        LinkedHashMap<Node, Double> methodSuspiciousness = getSuspiciousnessByMethod(original);
        List<CodeSnippet> selectedMethods = new LinkedList<>();
        int codeSize = 0;
        for (Map.Entry<Node, Double> entry : methodSuspiciousness.entrySet()) {
            Node method = entry.getKey();
            LineRange range = getRange(original, method);
            
            if (codeSize + range.size() < Configuration.INSTANCE.llm().maxCodeContext()) {
                selectedMethods.add(getSnippetForMethod(original, method));
                codeSize += range.size();
            } else {
                break;
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

    public class CodeSnippet {
        private Path file;
        private LineRange lineRange;
        private List<String> lines;
        private List<String> newLines;
        
        public CodeSnippet(Path file, LineRange lineRange, List<String> lines) {
            this.file = file;
            this.lineRange = lineRange;
            this.lines = lines;
        }
        
        public int size() {
            return lineRange.size();
        }
        
        public String getText() {
            return lines.stream().collect(Collectors.joining("\n"));
        }
        
        public boolean contains(ChangedArea changedArea) {
            return file.equals(Path.of(changedArea.file())) && lineRange.start() <= changedArea.start()
                    && lineRange.end() >= changedArea.end();
        }
    }

    private CodeSnippet getSnippetForMethod(Node root, Node method) {
        Path filename = null;
        List<Node> parents = root.getPath(method);
        for (Node parent : parents) {
            if (parent.getType() == Type.COMPILATION_UNIT) {
                filename = (Path) parent.getMetadata(Metadata.FILE_NAME);
                break;
            }
        }
        
        LineRange range = getRange(root, method);
        List<String> lines = Arrays.asList(method.getTextFormatted().split("\n"));
        
        return new CodeSnippet(filename, range, lines);
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
    
    private static record TestMethodContext(String code, String testClassName) {
    }

    private TestMethodContext getTestMethodContext(TestResult failingTest) {
        String testFileName = failingTest.testClass().substring(failingTest.testClass().lastIndexOf('.') + 1) + ".java";
        
        TestMethodContext result = null;
        try {
            List<Path> testFiles = Files.walk(projectRoot)
                    .filter(p -> p.getFileName().toString().equals(testFileName))
                    .filter(p -> Files.isRegularFile(p))
                    .toList();
            
            List<TestMethodContext> found = new LinkedList<>();
            for (Path testFile : testFiles) {
                TestMethodContext ctx = findTestMethodInFile(failingTest, testFile);
                if (ctx != null) {
                    found.add(ctx);
                }
            }
            
            if (found.size() == 1) {
                result = found.get(0);
            } else {
                if (testFiles.isEmpty()) {
                    LOG.warning(() -> "Could not find test file for test class " + failingTest.testClass());
                } else if (found.isEmpty()) {
                    LOG.warning(() -> "Could not find test method " + failingTest.testMethod() + " in any of these"
                            + " test files: " + testFiles);
                } else {
                    LOG.warning(() -> "Found test method " + failingTest.testMethod() + " more than once");
                }
            }
                
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read test method file", e);
        }
        return result;
    }

    private TestMethodContext findTestMethodInFile(TestResult failingTest, Path testFile)
            throws IOException {
        
        Node file = new Parser().parseSingleFile(testFile, encoding);
        Optional<Node> method = file.stream()
                .filter(n -> n.getType() == Type.METHOD)
                .filter(n -> n.getMetadata(Metadata.METHOD_NAME).equals(failingTest.testMethod()))
                .findAny();
        
        TestMethodContext result = null;
        if (method.isPresent()) {
            String code = method.get().getTextFormatted();
            List<Node> path = file.getPath(method.get());
            String testClassName = null;
            for (int i = path.size() - 1; i >= 0; i--) {
                if (path.get(i).getMetadata(Metadata.TYPE_NAME) != null) {
                    testClassName = (String) path.get(i).getMetadata(Metadata.TYPE_NAME);
                    break;
                }
            }
            
            result = new TestMethodContext(code, testClassName);
        }
        
        return result;
    }
    
    private String createProjectOutline(Node astRoot) {
        StringBuilder outline = new StringBuilder();
        recurseProjectOutline(astRoot, outline, "");
        return outline.toString();
    }
    
    private void recurseProjectOutline(Node node, StringBuilder outline, String indentation) {
        switch (node.getType()) {
        case TYPE:
            outline.append(indentation).append(AstUtils.getSignature(node)).append(" {\n");
            for (Node child : node.childIterator()) {
                recurseProjectOutline(child, outline, indentation + "    ");
            }
            outline.append(indentation).append("}\n");
            if (indentation.isEmpty()) {
                outline.append("\n");
            }
            break;
            
        case METHOD:
        case CONSTRUCTOR:
            outline.append(indentation).append(AstUtils.getSignature(node));
            if (node.stream().filter(n -> n.getType() == Type.COMPOSIT_STATEMENT).findAny().isPresent()) {
                outline.append(" {/*...*/}");
            }
            outline.append("\n");
            break;
            
        case ATTRIBUTE:
            outline.append(indentation).append(node.getTextSingleLine()).append("\n");
            break;
            
        case COMPILATION_UNIT:
            if (node.childCount() > 0 && node.get(0).childCount() > 0
                    && node.get(0).get(0) instanceof LeafNode leaf && leaf.getText().equals("package")) {
                outline.append(indentation).append(node.get(0).getTextSingleLine()).append("\n");
            }
            for (Node child : node.childIterator()) {
                recurseProjectOutline(child, outline, indentation);
            }
            break;
            
        default:
            for (Node child : node.childIterator()) {
                recurseProjectOutline(child, outline, indentation);
            }
            break;
        }
    }

    private String runQuery(ChatGptRequest query) throws IOException {
        try (Measurement.Probe m = Measurement.INSTANCE.start("llm-query")) {
            ChatGptResponse response = llm.send(query);
            return response.getContent();
        }
    }

    private void parseAnswerSnippets(String answer, List<CodeSnippet> snippets) throws AnswerDoesNotApplyException {
        Pattern pattern = Pattern.compile("Code snippet number (?<number>\\d+)", Pattern.CASE_INSENSITIVE);
        
        List<String> linesOutsideOfCode = new LinkedList<>();
        String[] answerLines = answer.split("\n");
        for (int i = 0; i < answerLines.length; i++) {
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
                    if (!answerLines[i].isEmpty()) {
                        linesOutsideOfCode.add(answerLines[i]);
                    }
                    continue;
                }
            }
            
            int snippetNumber = Integer.parseInt(m.group("number"));
            if (snippetNumber <= 0 || snippetNumber > snippets.size()) {
                throw new AnswerDoesNotApplyException("invalid snippet number " + snippetNumber);
            }
            
            i++;
            if (i >= answerLines.length || !answerLines[i].startsWith("```")) {
                throw new AnswerDoesNotApplyException("missing code block start marker for snippet " + snippetNumber);
            }
            i++;
            
            List<String> lines = new LinkedList<>();
            for (; i < answerLines.length; i++) {
                if (answerLines[i].startsWith("```")) {
                    break;
                }
                lines.add(answerLines[i]);
            }
            if (i == answerLines.length) {
                throw new AnswerDoesNotApplyException("missing code block end marker for snippet " + snippetNumber);
            }
            
            snippets.get(snippetNumber - 1).newLines = lines;
        }
        
        if (!linesOutsideOfCode.isEmpty()) {
            LOG.warning(() -> "Found answer lines outside of code blocks:\n"
                    + linesOutsideOfCode.stream().collect(Collectors.joining("\n")));
        }
    }
    
    private void writeModifiedFile(Path file, List<CodeSnippet> modifiedCodeSnippets) throws IOException {
        List<String> oldLines = Files.readAllLines(file, encoding);
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
