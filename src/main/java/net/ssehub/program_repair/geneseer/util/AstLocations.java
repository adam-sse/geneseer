package net.ssehub.program_repair.geneseer.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.ssehub.program_repair.geneseer.code.AstUtils;
import net.ssehub.program_repair.geneseer.code.Node;
import net.ssehub.program_repair.geneseer.code.Node.Type;

public class AstLocations {

    private Map<Integer, Set<Node>> statementsByLine;
    
    private Map<Integer, Set<Node>> methodsByLine;
    
    public AstLocations(Node fileNode) {
        if (fileNode.getType() != Type.COMPILATION_UNIT) {
            throw new IllegalArgumentException("Can only create AstLocations for node typ COMPILATION_UNIT, got "
                    + fileNode.getType());
        }
        
        statementsByLine = new HashMap<>((int) fileNode.stream().filter(n -> n.getType() == Type.STATEMENT).count());
        methodsByLine = new HashMap<>((int) fileNode.stream()
                .filter(n -> n.getType() == Type.METHOD || n.getType() == Type.CONSTRUCTOR)
                .count());
        
        for (Iterator<Node> it = fileNode.stream().iterator(); it.hasNext();) {
            Node node = it.next();
            
            if (node.getType() == Type.STATEMENT || node.getType() == Type.METHOD
                    || node.getType() == Type.CONSTRUCTOR) {
                
                int startingLine = AstUtils.getLine(fileNode, node);
                int endLine = startingLine + AstUtils.getAdditionalLineCount(node);
                
                Map<Integer, Set<Node>> target;
                if (node.getType() == Type.STATEMENT) {
                    target = statementsByLine;
                } else {
                    target = methodsByLine;
                }
                
                for (int line = startingLine; line <= endLine; line++) {
                    target.computeIfAbsent(line, l -> new HashSet<>()).add(node);
                }
            }
        }
    }
    
    public Set<Node> getStatementsAtLine(int line) {
        return statementsByLine.getOrDefault(line, Collections.emptySet());
    }
    
    public Set<Node> getMethodsAtLine(int line) {
        return methodsByLine.getOrDefault(line, Collections.emptySet());
    }
    
}
