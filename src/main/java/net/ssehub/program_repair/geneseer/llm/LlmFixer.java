package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
import net.ssehub.program_repair.geneseer.Configuration.LlmConfiguration.ProjectOutline;
import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.LeafNode;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.code.Parser;
import net.ssehub.program_repair.geneseer.code.Writer;
import net.ssehub.program_repair.geneseer.evaluation.TestResult;
import net.ssehub.program_repair.geneseer.util.AstDiff;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class LlmFixer {

    private static final Logger LOG = Logger.getLogger(LlmFixer.class.getName());
    
    private static final String SYSTEM_MESSAGE = "You are an automated program repair tool for Java programs.";
    
    private ILlm llm;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Charset encoding;
    
    private Path projectRoot;
    
    private ISnippetRanker ranker;
    
    private int numberOfCalls;
    
    private int numberOfAnswers;
    
    private int numberOfTimeouts;
    
    private long totalQueryTokens;
    
    private long totalAnswerTokens;

    public LlmFixer(ILlm llm, ISnippetRanker ranker, TemporaryDirectoryManager tempDirManager, Charset encoding,
            Path projectRoot) {
        this.llm = llm;
        this.tempDirManager = tempDirManager;
        this.encoding = encoding;
        this.projectRoot = projectRoot;
        this.ranker = ranker;
    }

    public Optional<Node> createVariant(Node original, List<TestResult> failingTests) throws IOException {
        List<CodeSnippet> codeSnippets = selectMostSuspiciousMethods(original, failingTests);
        Query query = createQuery(original, failingTests, codeSnippets);
        String answer = runQuery(query);
        LOG.fine(() -> "Got answer:\n" + answer);
        
        Node variant = original.clone();
        Path sourceDir = null;
        try {
            parseAnswerSnippets(answer, codeSnippets);
            
            Map<Path, List<CodeSnippet>> modifiedSnippetsByFile = new HashMap<>(codeSnippets.size());
            for (CodeSnippet snippet : codeSnippets) {
                if (snippet.getNewLines() != null) {
                    List<CodeSnippet> snippetsInFile = modifiedSnippetsByFile.getOrDefault(
                            snippet.getFile(), new LinkedList<>());
                    snippetsInFile.add(snippet);
                    modifiedSnippetsByFile.put(snippet.getFile(), snippetsInFile);
                }
            }
            
            LOG.info(() -> "LLM answer has modified " + codeSnippets.stream()
                    .filter(s -> s.getNewLines() != null)
                    .count() + " snippets in " + modifiedSnippetsByFile.size() + " files");
            
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

    public Query createQuery(Node code, List<TestResult> failingTests, List<CodeSnippet> codeSnippets) {
        List<TestMethodContext> testMethodContext = TestMethodContext.constructContext(failingTests,
                projectRoot, encoding);
        String projectOutline = null;
        if (Configuration.INSTANCE.llm().projectOutine() != ProjectOutline.NONE) {
            projectOutline = createProjectOutline(code,
                    Configuration.INSTANCE.llm().projectOutine() == ProjectOutline.FULL ? null : codeSnippets);
        }
        
        Query query = new Query();
        query.addMessage(new Message(Role.SYSTEM, SYSTEM_MESSAGE));
        
        StringBuilder prompt = new StringBuilder();
        prompt.append("Your task is to fix the bug that is causing the following test case failure");
        if (testMethodContext.size() > 1) {
            prompt.append("s.\n\n");
        } else {
            prompt.append(".\n\n");
        }
        writeFailingTestCases(prompt, testMethodContext);
        writeProjectOutline(prompt, projectOutline);
        writeCodeSnippets(prompt, codeSnippets);
        prompt.append("\n\nOutput the fixed code snippets! You must prefix your fixed code with"
                + " \"Code snippet number <number>\" to clarify which code snippet you modified (you may modify"
                + " multiple code snippets). Do not output code snippets that you did not modify. Surround each code"
                + " snippet with ``` markers (after the code snippet number). Output the complete fixed code that is"
                + " given to you, even if only a small part of it is changed.");
        
        LOG.fine(() -> "Prompt:\n" + prompt);
        query.addMessage(new Message(Role.USER, prompt.toString()));
        if (Configuration.INSTANCE.llm().seed() != null) {
            query.setSeed(Configuration.INSTANCE.llm().seed());
        }
        return query;
    }

    static void writeFailingTestCases(StringBuilder prompt, List<TestMethodContext> testMethodContext) {
        int testNumber = 1;
        for (TestMethodContext testContext : testMethodContext) {
            prompt.append("Failing test ");
            if (testMethodContext.size() > 1) {
                prompt.append("number ").append(testNumber).append(' ');
            }
            prompt.append(testContext.testResult().testMethod())
                    .append("() in ")
                    .append(testContext.testResult().testClass())
                    .append('\n');
            if (testContext.code() != null) {
                prompt.append("Test code:\n```java\n");
                prompt.append(testContext.code());
                prompt.append("\n```\n");
            }
            prompt.append("Failure message:\n```\n");
            if (testContext.testResult().failureMessage() != null) {
                prompt.append(testContext.testResult().failureMessage());
            } else {
                prompt.append(testContext.testResult().failureStacktrace());
            }
            prompt.append("\n```\n\n");
            testNumber++;
        }
    }

    private static void writeProjectOutline(StringBuilder prompt, String projectOutline) {
        if (projectOutline != null) {
            prompt.append("Here is a");
            if (Configuration.INSTANCE.llm().projectOutine() == ProjectOutline.PARTIAL) {
                prompt.append(" partial");
            } else {
                prompt.append("n");
            }
            prompt.append(" overview of the project:\n```java\n");
            prompt.append(projectOutline);
            prompt.append("```\n\n");
        }
    }

    private static void writeCodeSnippets(StringBuilder prompt, List<CodeSnippet> codeSnippets) {
        prompt.append("Here are " + codeSnippets.size() + " code snippets that may need to be fixed:");
        int index = 1;
        for (CodeSnippet snippet : codeSnippets) {
            prompt.append("\n\n");
            prompt.append("File ").append(snippet.getFile()).append('\n');
            prompt.append("Code snippet number ").append(index++).append(":\n```java\n");
            prompt.append(snippet.getText()).append('\n');
            prompt.append("```");
        }
    }

    public List<CodeSnippet> selectMostSuspiciousMethods(Node original, List<TestResult> failingTests)
            throws IOException {
        
        List<CodeSnippet> selected = ranker.selectCodeSnippets(original, ranker.needsTestMethodContext()
                ? TestMethodContext.constructContext(failingTests, projectRoot, encoding) : null);
        return selected;
    }
    
    static String createProjectOutline(Node astRoot, List<CodeSnippet> filterByCodeSnippets) {
        StringBuilder outline = new StringBuilder();
        
        for (Node file : astRoot.childIterator()) {
            if (file.getType() != Type.COMPILATION_UNIT || file.getMetadata(Metadata.FILE_NAME) == null) {
                throw new RuntimeException("Node under AST root is not a compilation unit: " + file);
            }
            Path fileName = (Path) file.getMetadata(Metadata.FILE_NAME);
            if (filterByCodeSnippets == null || filterByCodeSnippets.stream()
                        .filter(snippet -> snippet.getFile().equals(fileName))
                        .findAny().isPresent()) {
                if (file.childCount() > 0 && file.get(0).childCount() > 0
                        && file.get(0).get(0) instanceof LeafNode leaf && leaf.getText().equals("package")) {
                    outline.append(file.get(0).getTextSingleLine()).append("\n");
                }
                recurseProjectOutline(file, outline, "");
            } else {
                LOG.finer(() -> "Filtering out " + fileName + " from outline because no code snippet references it");
            }
        }
        
        if (outline.length() > 2
                && outline.charAt(outline.length() - 1) == '\n' && outline.charAt(outline.length() - 2) == '\n') {
            outline.deleteCharAt(outline.length() - 1);
        }
        return outline.toString();
    }
    
    private static void recurseProjectOutline(Node node, StringBuilder outline, String indentation) {
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
            String text = node.getTextSingleLine();
            int assignmentIndex = text.indexOf('=');
            if (assignmentIndex != -1) {
                text = text.substring(0, assignmentIndex).trim() + ';';
            }
            outline.append(indentation).append(text).append("\n");
            break;
            
        default:
            for (Node child : node.childIterator()) {
                recurseProjectOutline(child, outline, indentation);
            }
            break;
        }
    }
    
    private String runQuery(Query query) throws IOException {
        try (Measurement.Probe m = Measurement.INSTANCE.start("llm-query")) {
            LOG.info("Sending query to LLM: " + query);
            numberOfCalls++;
            IResponse response = llm.send(query);
            if (response.getThinking() != null) {
                LOG.fine(() -> "Got " + response.getThinking().length() + " characters of thinking trace");
            }
            numberOfAnswers++;
            totalQueryTokens += response.getQueryTokens();
            totalAnswerTokens += response.getAnswerTokens();
            return response.getContent();
        } catch (HttpTimeoutException e) {
            numberOfTimeouts++;
            throw e;
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
            
            snippets.get(snippetNumber - 1).setNewLines(lines);
        }
        
        if (!linesOutsideOfCode.isEmpty()) {
            LOG.info(() -> "Found answer lines outside of code blocks:\n"
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
                if (lineNumber == snippet.getLineRange().start()) {
                    wasInAnySnippet = true;
                    newLines.addAll(snippet.getNewLines());
                    i = snippet.getLineRange().end() - 1;
                }
            }
            
            if (!wasInAnySnippet) {
                newLines.add(oldLines.get(i));
            }
        }
        
        Files.writeString(file, newLines.stream().collect(Collectors.joining("\n")), encoding);
    }
    
    public Map<String, Object> createStats() {
        return Map.of(
                "calls", numberOfCalls,
                "answers", numberOfAnswers,
                "timeouts", numberOfTimeouts,
                "totalQueryTokens", totalQueryTokens,
                "totalAnswerTokens", totalAnswerTokens
                );
    }
    
}
