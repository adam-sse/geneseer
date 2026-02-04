package net.ssehub.program_repair.geneseer.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.ssehub.program_repair.geneseer.parsing.AstUtils;
import net.ssehub.program_repair.geneseer.parsing.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.Node;
import net.ssehub.program_repair.geneseer.parsing.Node.Type;

public class AstLocations {

    private Map<Integer, Set<Node>> statementsByLine;
    
    public AstLocations(Node fileNode) {
        if (fileNode.getType() != Type.COMPILATION_UNIT) {
            throw new IllegalArgumentException("Can only create AstLocations for node typ COMPILATION_UNIT, got "
                    + fileNode.getType());
        }
        
        statementsByLine = new HashMap<>((int) fileNode.stream().filter(n -> n.getType() == Type.STATEMENT).count());
        
        int currentLine = 1;
        for (Iterator<Node> it = fileNode.stream().iterator(); it.hasNext();) {
            Node node = it.next();
            
            if (node.getType() == Type.LEAF) {
                currentLine += ((LeafNode) node).getPrefixNewlines();
            } else if (node.getType() == Type.STATEMENT) {
                int startingLine = currentLine + AstUtils.getFirstLeafNode(node).getPrefixNewlines();
                int endLine = startingLine + AstUtils.getAdditionalLineCount(node);
                for (int line = startingLine; line <= endLine; line++) {
                    statementsByLine.computeIfAbsent(line, l -> new HashSet<>())
                            .add(node);
                }
            }
        }
    }
    
    public Set<Node> getStatementsAtLine(int line) {
        return statementsByLine.getOrDefault(line, Collections.emptySet());
    }
    
}
