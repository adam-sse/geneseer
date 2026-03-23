package net.ssehub.program_repair.geneseer.llm;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Metadata;
import net.ssehub.program_repair.geneseer.code.Node.Type;

public class SuspiciousnessRanker extends AbstractSnippetRanker {

    public SuspiciousnessRanker(int lineLimit) {
        super(lineLimit);
    }

    @Override
    public LinkedHashMap<Node, Double> rankMethods(Node code, List<TestMethodContext> failingTestMethods) {
        List<Node> methods = code.stream()
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
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue())) // descending suspiciousness
                .forEach(e -> sortedSuspiciousness.put(e.getKey(), e.getValue()));
        return sortedSuspiciousness;
    }

}
