package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import net.ssehub.program_repair.geneseer.evaluation.TestResult;
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
    
    private static final String SYSTEM_MESSAGE = """
            You are an automated program repair tool. Write no explanations and only output a unified diff. The diff
            should have no header.
            """;

    private ChatGptConnection llm;
    
    private TemporaryDirectoryManager tempDirManager;
    
    private Charset encoding;
    
    private Path projectRoot;
    
    public LlmFixer(ChatGptConnection llm, TemporaryDirectoryManager tempDirManager, Charset encoding,
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
        
        String diff = queryForDiff(failingTests.stream().map(TestResult::failureMessage).toList(),
                failingTests.stream().map(this::getTestMethodContext).toList(),
                codeContext.contextLines());
        
        LOG.fine(() -> "Got diff:\n" + diff);
        
        Optional<Node> result;
        try {
            List<String> newFileContent = applyDiff(diff, Files.readAllLines(codeContext.file(), encoding));
            
            Files.writeString(codeContext.file(), newFileContent.stream().collect(Collectors.joining("\n")), encoding);
            
            Node variant = Parser.parse(sourceDir, encoding);
            result = Optional.of(variant);
            
            try {
                String astDiff = AstDiff.getDiff(original, variant, tempDirManager, encoding);
                LOG.info(() -> "Diff of created variant:\n" + astDiff);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to create diff of variant", e);
            }
            
        } catch (PatchDoesNotApplyException e) {
            LOG.log(Level.WARNING, "Patch does not apply", e);
            result = Optional.empty();
        }
        tempDirManager.deleteTemporaryDirectory(sourceDir);
        
        return result;
    }
    
    private record Context(Path file, List<String> contextLines) {
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
        
        return new Context(file, lines.subList(range.start() - 1, range.end()));
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
    
    private record LineRange(int start, int end) {
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
    
    private String queryForDiff(List<String> failureMessages, List<List<String>> testMethodContexts,
            List<String> code) throws IOException {
        ChatGptRequest request = new ChatGptRequest(LlmConfiguration.INSTANCE.getModel());
        request.addMessage(new ChatGptMessage(SYSTEM_MESSAGE, "system"));
        
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
        request.addMessage(new ChatGptMessage(query.toString(), "user"));
        
        ChatGptResponse response = llm.send(request);
        if (response.choices().size() > 1) {
            LOG.warning(() -> "Got more than 1 choice: " + response);
        }
        if (response.choices().isEmpty()) {
            throw new IOException("Got no choices in response " + response);
        }
        
        String diff = response.choices().get(0).message().content();
        if (diff.startsWith("```") && diff.indexOf('\n') != -1) {
            diff = diff.substring(diff.indexOf('\n') + 1);
            if (diff.endsWith("\n```")) {
                diff = diff.substring(0, diff.length() - 4);
            }
        }
        
        return diff;
    }
    
    private static List<String> applyDiff(String diff, List<String> originalFileContent)
            throws PatchDoesNotApplyException {
        
        List<String> diffLines = Arrays.asList(diff.split("\n"));
        List<String> patchedFile = new LinkedList<>();
        
        int originalFileContentIndex = 0;
        for (String diffLine : diffLines) {
            if (diffLine.startsWith("@@") || diffLine.startsWith("+++") || diffLine.startsWith("---")) {
                continue;
            }
            
            if (diffLine.startsWith("+")) {
                patchedFile.add(diffLine.substring(1));
                
            } else if (diffLine.startsWith("-")) {
                diffLine = diffLine.substring(1).trim();
                String currentFileLine = originalFileContent.get(originalFileContentIndex).trim();
                while (currentFileLine.isEmpty() && !diffLine.equalsIgnoreCase(diffLine)) {
                    patchedFile.add(originalFileContent.get(originalFileContentIndex));
                    originalFileContentIndex++;
                }
                
                if (!diffLine.equalsIgnoreCase(currentFileLine)) {
                    throw new PatchDoesNotApplyException("Removed line from diff \"" + diffLine
                            + "\" does not match with file content \"" + currentFileLine + "\"");
                } else {
                    originalFileContentIndex++;
                }
                
            } else {
                diffLine = diffLine.trim().toLowerCase();
                String currentFileLine = originalFileContent.get(originalFileContentIndex).trim();
                while (!currentFileLine.equalsIgnoreCase(diffLine)) {
                    patchedFile.add(originalFileContent.get(originalFileContentIndex));
                    originalFileContentIndex++;
                    if (originalFileContentIndex < originalFileContent.size()) {
                        currentFileLine = originalFileContent.get(originalFileContentIndex).trim();
                    } else {
                        break;
                    }
                }
                
                if (originalFileContentIndex >= originalFileContent.size()) {
                    throw new PatchDoesNotApplyException("Can't find diff line in original file: " + diffLine);
                }
                
                patchedFile.add(originalFileContent.get(originalFileContentIndex));
                originalFileContentIndex++;
            }
        }
        
        while (originalFileContentIndex < originalFileContent.size()) {
            patchedFile.add(originalFileContent.get(originalFileContentIndex));
            originalFileContentIndex++;
        }
        
        return patchedFile;
    }
    
}
