package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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

public abstract class AbstractLlmFixer {

    private static final Logger LOG = Logger.getLogger(AbstractLlmFixer.class.getName());
    
    private IChatGptConnection llm;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Charset encoding;
    
    private Path projectRoot;

    public AbstractLlmFixer(IChatGptConnection llm, TemporaryDirectoryManager tempDirManager, Charset encoding,
            Path projectRoot) {
        this.llm = llm;
        this.tempDirManager = tempDirManager;
        this.encoding = encoding;
        this.projectRoot = projectRoot;
    }

    public Optional<Node> createVariant(Node original, List<TestResult> failingTests) throws IOException {
        Path sourceDir = tempDirManager.createTemporaryDirectory();
        Writer.write(original, null, sourceDir, encoding);
        
        Context codeContext = getContextOfMostSuspiciousStatement(original, sourceDir);
        
        String answer = query(failingTests.stream().map(TestResult::failureMessage).toList(),
                failingTests.stream().map(this::getTestMethodContext).toList(),
                codeContext.contextLines());
        
        LOG.fine(() -> "Got answer:\n" + answer);
        
        Optional<Node> result;
        try {
            List<String> newFileContent = applyAnswer(answer, Files.readAllLines(codeContext.file(), encoding),
                    codeContext.lineRange);
            
            Files.writeString(codeContext.file(), newFileContent.stream().collect(Collectors.joining("\n")), encoding);
            
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

    private record Context(Path file, LineRange lineRange,  List<String> contextLines) {
    }

    private Context getContextOfMostSuspiciousStatement(Node original, Path sourceDir) throws IOException {
        List<Node> suspiciousNodes = original.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .sorted(Comparator.comparingDouble((Node n) -> (double) n.getMetadata(Metadata.SUSPICIOUSNESS))
                        .reversed())
                .toList();
        
        Node mostSuspicious = suspiciousNodes.get(0);
        Location location = getPosition(original, mostSuspicious);
        
        LineRange range = getRange(mostSuspicious);
        List<Node> parents = original.getPath(mostSuspicious);
        for (int i = parents.size() - 1; i >= 0; i--) {
            Node parent = parents.get(i);
            LineRange parentRange = getRange(parent);
            
            if (parentRange.size() <= LlmConfiguration.INSTANCE.getMaxCodeContext()) {
                range = parentRange;
            } else {
                break;
            }
        }
        
        Path file = sourceDir.resolve(location.file());
        List<String> lines = Files.readAllLines(file, encoding);
        
        return new Context(file, range, lines.subList(range.start() - 1, range.end()));
    }

    private record Location(Path file, int line) {
    }

    private Location getPosition(Node original, Node n) {
        Node leaf = n;
        while (leaf.getType() != Type.LEAF) {
            leaf = leaf.get(0);
        }
        Position positionInFile = ((LeafNode) leaf).getOriginalPosition();
        
        List<Node> parents = original.getPath(n);
        Path file = null;
        for (Node parent : parents) {
            if (parent.getMetadata(Metadata.FILENAME) != null) {
                file = (Path) parent.getMetadata(Metadata.FILENAME);
                break;
            }
        }
        
        return new Location(file, positionInFile.line());
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
            Optional<Path> testFile = Files.walk(projectRoot)
                    .filter(p -> p.getFileName().toString().equals(testFileName))
                    .filter(p -> Files.isRegularFile(p))
                    .findAny();
            
            if (testFile.isPresent()) {
                List<String> lines = Files.readAllLines(testFile.get(), encoding);
                int startLine = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(failingTest.testMethod())) {
                        startLine = i;
                        break;
                    }
                }
                
                int endLine = -1;
                if (startLine != -1) {
                    int bracketDepth = 0;
                    boolean foundFirst = false;
                    for (int i = startLine; i < lines.size(); i++) {
                        String line = lines.get(i);
                        for (char ch : line.toCharArray()) {
                            if (ch == '{') {
                                bracketDepth++;
                                foundFirst = true;
                            } else if (ch == '}') {
                                bracketDepth--;
                            }
                        }
                        if (foundFirst && bracketDepth == 0) {
                            endLine = i;
                            break;
                        }
                    }
                }
                
                if (startLine != -1 && endLine != -1) {
                    result = lines.subList(startLine, endLine + 1);
                } else {
                    LOG.fine(() -> "Couldn't find test method " + failingTest.testMethod() + " in file:\n"
                            + lines.stream().collect(Collectors.joining("\n")));
                }
                
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to read test method file", e);
        }
        return result;
    }

    private String query(List<String> failureMessages, List<List<String>> testMethodContexts,
            List<String> code) throws IOException {
        ChatGptRequest request = new ChatGptRequest(LlmConfiguration.INSTANCE.getModel());
        request.addMessage(new ChatGptMessage(getSysteMessage(), Role.SYSTEM));
        
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
        
        query.append("Code to fix:\n```java\n");
        code.stream().forEach(line -> query.append(line).append('\n'));
        query.append("```");
        
        
        LOG.fine(() -> "Query:\n" + query);
        request.addMessage(new ChatGptMessage(query.toString(), Role.USER));
        if (LlmConfiguration.INSTANCE.getTemperature() != null) {
            request.setTemperature(LlmConfiguration.INSTANCE.getTemperature());
        }
        if (LlmConfiguration.INSTANCE.getSeed() != null) {
            request.setSeed(LlmConfiguration.INSTANCE.getSeed());
        }
        
        ChatGptResponse response = llm.send(request);
        return removeCodeBlockAroundAnswer(response.getContent());
    }
    
    private String removeCodeBlockAroundAnswer(String answer) {
        if (answer.startsWith("```") && answer.indexOf('\n') != -1 && answer.endsWith("\n```")) {
            answer = answer.substring(answer.indexOf('\n') + 1, answer.length() - 4);
        }
        return answer;
    }

    protected abstract String getSysteMessage();
    
    protected abstract List<String> applyAnswer(String answer, List<String> originalFileContent,
            LineRange submittedRange) throws AnswerDoesNotApplyException;
    
}
