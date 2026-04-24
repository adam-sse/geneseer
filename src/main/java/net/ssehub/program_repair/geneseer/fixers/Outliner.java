package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.Result;
import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;
import net.ssehub.program_repair.geneseer.defects4j.PatchWriter;
import net.ssehub.program_repair.geneseer.defects4j.PatchWriter.ChangedArea;
import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.CodeSnippet;
import net.ssehub.program_repair.geneseer.llm.ILlm;
import net.ssehub.program_repair.geneseer.llm.LlmBasedFileRanker;
import net.ssehub.program_repair.geneseer.llm.RagRanker;
import net.ssehub.program_repair.geneseer.llm.TestMethodContext;
import net.ssehub.program_repair.geneseer.util.JsonUtils;

public class Outliner implements IFixer {

    public static final String METHOD_OVERVIEW_FILENAME = "geneseer-method-overview.json";
    
    public static final String FILE_OVERVIEW_FILENAME = "geneseer-file-overview.json";
    
//    private static final Logger LOG = Logger.getLogger(Outliner.class.getName());
    
    private Path projectRoot;
    
    private Path sourceDirectory;
    
    private Charset encoding;
    
    private Encoding tokenEncoding;
    
    private ILlm llm;
    
    public Outliner(Path projectRoot, Path sourceDirectory, Charset encoding) {
        this.projectRoot = projectRoot;
        this.sourceDirectory = sourceDirectory;
        this.encoding = encoding;
        this.tokenEncoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
    }
    
    public void setLlm(ILlm llm) {
        this.llm = llm;
    }
    
    @Override
    public Node run(Node ast, TestSuite testSuite, Result result) throws IOException {
        List<ChangedArea> changedByHumanPatch = JsonUtils.parseToListFromFile(
                projectRoot.resolve(PatchWriter.CHANGED_AREAS_FILENAME), ChangedArea.class);
        
        List<TestMethodContext> testContext = TestMethodContext.constructContext(
                testSuite.getInitialFailingTestResults(), projectRoot, encoding);
        
        
        createMethodOverivew(ast, testContext, changedByHumanPatch);
        createFileOverview(ast, testContext);
        
        result.setResult("DONE");
        return null;
    }
    
    public static record Method(
            String className,
            String methodName,
            String signature,
            String file,
            int lineStart,
            int lineEnd,
            int tokens,
            int numStatements,
            boolean modifiedByHumanPatch,
            int suspiciousCount,
            double suspiciousMax,
            double suspiciousSum,
            double suspiciousAvg,
            Double ragDistance) {
        
        public int lines() {
            return lineEnd - lineStart + 1;
        }
        
    }

    private void createMethodOverivew(Node ast, List<TestMethodContext> testContext,
            List<ChangedArea> changedByHumanPatch) throws IOException {
        
        RagRanker ragRanker = new RagRanker(projectRoot, Integer.MAX_VALUE,
                Configuration.INSTANCE.rag().model(), Configuration.INSTANCE.rag().api());
        LinkedHashMap<Node, Double> ragDistances = ragRanker.rankMethods(ast, testContext);
        
        List<Method> methods = ast.stream()
                .filter(n -> n.getType() == Type.METHOD || n.getType() == Type.CONSTRUCTOR)
                .map(method -> createMethodStats(method, ast, ragDistances, changedByHumanPatch))
                .toList();
        
        JsonUtils.writeJson(methods, projectRoot.resolve(METHOD_OVERVIEW_FILENAME));
    }
    
    private Method createMethodStats(Node method, Node ast, LinkedHashMap<Node, Double> ragDistances,
            List<ChangedArea> changedByHumanPatch) {
        
        String methodName = (String) method.getMetadata(Metadata.METHOD_NAME);
        String signature = AstUtils.getSignature(method);
        
        List<Node> parents = ast.getPath(method);
        int classIndex = parents.size() - 2;
        while (parents.get(classIndex).getMetadata(Metadata.TYPE_NAME) == null && classIndex > 0) {
            classIndex--;
        }
        String className = (String) parents.get(classIndex).getMetadata(Metadata.TYPE_NAME);
        
        int fileIndex = parents.size() - 2;
        while (parents.get(fileIndex).getMetadata(Metadata.FILE_NAME) == null && fileIndex > 0) {
            fileIndex--;
        }
        String file = parents.get(fileIndex).getMetadata(Metadata.FILE_NAME).toString();
        
        int lineStart =  AstUtils.getLine(ast, method);
        int lineEnd = lineStart + AstUtils.getAdditionalLineCount(method);
        
        int numTokens = tokenEncoding.countTokensOrdinary(method.getTextFormatted());
        
        int numStatements = (int) method.stream()
                .filter(n -> n.getType() == Type.STATEMENT)
                .count();
        
        boolean modifiedByHumanPatch = changedByHumanPatch.stream()
                .filter(area -> file.equals(area.file()))
                .filter(area -> area.start() <= lineEnd && area.end() >= lineStart)
                .findAny().isPresent();
        
        DoubleSummaryStatistics stats = method.stream()
                .filter(n -> n.getMetadata(Metadata.SUSPICIOUSNESS) != null)
                .mapToDouble(n -> (double) n.getMetadata(Metadata.SUSPICIOUSNESS))
                .summaryStatistics();
        
        double maxSus = stats.getCount() > 0 ? stats.getMax() : 0.0;
        double avgSus = stats.getCount() > 0 ? maxSus / stats.getCount() : 0.0;
        
        return new Method(className, methodName, signature,
                file, lineStart, lineEnd, numTokens, numStatements, modifiedByHumanPatch,
                (int) stats.getCount(), maxSus, stats.getSum(), avgSus, ragDistances.get(method));
    }
    
    public record File(String path, int lines, int tokens, Integer llmRanking) {
    }
    
    private void createFileOverview(Node ast, List<TestMethodContext> testContext) throws IOException {
        Map<Path, Integer> llmRanking = new HashMap<>();
        if (llm != null) {
            LlmBasedFileRanker llmFileRanker = new LlmBasedFileRanker(llm);
            List<CodeSnippet> selectedSnippets = llmFileRanker.selectCodeSnippets(ast, testContext);
            for (int i = 0; i < selectedSnippets.size(); i++) {
                llmRanking.put(selectedSnippets.get(i).getFile(), i + 1);
            }
        }
        
        List<File> files = Files.walk(sourceDirectory)
                .filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(".java"))
                .map(f -> createFileStats(sourceDirectory, f, llmRanking))
                .toList();
        
        JsonUtils.writeJson(files, projectRoot.resolve(FILE_OVERVIEW_FILENAME));
    }
    
    private File createFileStats(Path sourceDirectory, Path absoluteFilePath, Map<Path, Integer> llmRanking)
            throws UncheckedIOException {
        int numLines;
        int numTokens;
        try {
            List<String> lines = Files.readAllLines(absoluteFilePath, encoding);
            numLines = lines.size();
            String fullFile = lines.stream().collect(Collectors.joining("\n"));
            numTokens = tokenEncoding.countTokensOrdinary(fullFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        
        Path relativeFilePath = sourceDirectory.relativize(absoluteFilePath);
        Integer llmRank = llmRanking.get(relativeFilePath);
        return new File(relativeFilePath.toString(), numLines, numTokens, llmRank);
    }
    
}
