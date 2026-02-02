package net.ssehub.program_repair.geneseer.fixers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import net.ssehub.program_repair.geneseer.evaluation.TestSuite;
import net.ssehub.program_repair.geneseer.llm.ChangedArea;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.util.AstUtils;

public class Outliner implements IFixer {
    
//    private static final Logger LOG = Logger.getLogger(Outliner.class.getName());
    
    private Path projectRoot;
    
    public Outliner(Path projectRoot) {
        this.projectRoot = projectRoot;
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
            double suspiciousAvg) {
        
        public int lines() {
            return lineEnd - lineStart + 1;
        }
        
    }

    @Override
    public Node run(Node ast, TestSuite testSuite, Map<String, Object> result) throws IOException {
        Path changedAreasFile = projectRoot.resolve("geneseer-changed-areas.json");
        java.lang.reflect.Type listType = new TypeToken<List<ChangedArea>>() {
        }.getType();
        List<ChangedArea> changedByHumanPatch = new Gson().fromJson(
                Files.readString(changedAreasFile, StandardCharsets.UTF_8), listType);
        
        Encoding tokenEncoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        
        List<Method> methods = ast.stream()
                .filter(n -> n.getType() == Type.METHOD || n.getType() == Type.CONSTRUCTOR)
                .map(method -> {
                    String methodName = (String) method.getMetadata(Metadata.METHOD_NAME);
                    String signature = AstUtils.getMethodSignature(method);
                    
                    List<Node> parents = ast.getPath(method);
                    int classIndex = parents.size() - 2;
                    while (parents.get(classIndex).getMetadata(Metadata.CLASS_NAME) == null && classIndex > 0) {
                        classIndex--;
                    }
                    String className = (String) parents.get(classIndex).getMetadata(Metadata.CLASS_NAME);
                    
                    int fileIndex = parents.size() - 2;
                    while (parents.get(fileIndex).getMetadata(Metadata.FILE_NAME) == null && fileIndex > 0) {
                        fileIndex--;
                    }
                    String file = parents.get(fileIndex).getMetadata(Metadata.FILE_NAME).toString();
                    
                    int lineStart =  AstUtils.getLine(ast, method);
                    int lineEnd = lineStart + AstUtils.getAdditionalLineCount(method);
                    
                    int numTokens = tokenEncoding.countTokensOrdinary(AstUtils.getFormattedText(method));
                    
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
                            (int) stats.getCount(), maxSus, stats.getSum(), avgSus);
                })
                .toList();
        
        Files.writeString(projectRoot.resolve("geneseer-method-overview.json"),
                new Gson().toJson(methods), StandardCharsets.UTF_8);
        
        result.put("result", "DONE");
        return null;
    }
    
}
