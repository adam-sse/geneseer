package net.ssehub.program_repair.geneseer.parsing.visualization;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.JComponent;

import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;

public class TreeVisualization extends JComponent {

    private static final long serialVersionUID = 4294427171296768764L;
    
    private static final int NODE_SIZE = 4;

    private static final int DEFAULT_VERTICAL_GAP = 20;
    
    private static final int HORIZONTAL_GAP = 2;
    
    private static final int PADDING = 0;

    private Node root;
    
    private Node compareWith;
    
    private int verticalGap;
    
    private int maxDepth;
    
    private List<Map<Point, Node>> nodes;
    
    private Graphics currentGraphics;
    
    public TreeVisualization(Node root, Node compareWith) {
        if (compareWith != null) {
            InnerNode newRoot = new InnerNode(root.getType());
            InnerNode newCompareWith = new InnerNode(compareWith.getType());
            for (int i = 0; i < Math.min(root.childCount(), compareWith.childCount()); i++) {
                if (root.get(i) != compareWith.get(i)) {
                    newRoot.add(root.get(i));
                    newCompareWith.add(compareWith.get(i));
                }
            }
            
            for (int i = root.childCount(); i < compareWith.childCount(); i++) {
                newCompareWith.add(compareWith.get(i));
            }
            for (int i = compareWith.childCount(); i < root.childCount(); i++) {
                newRoot.add(root.get(i));
            }
            
            root = newRoot;
            compareWith = newCompareWith;
        }
        
        this.root = root;
        this.compareWith = compareWith;
        this.maxDepth = getMaxDepth(root);
        
        setPreferredSize(new Dimension(getWidth(root) + PADDING * 2, maxDepth * DEFAULT_VERTICAL_GAP + PADDING * 2));
        
        nodes = new ArrayList<>(maxDepth);
        for (int i = 0; i < maxDepth; i++) {
            nodes.add(new HashMap<>());
        }
        
        setToolTipText("");
    }
    
    @Override
    public String getToolTipText(MouseEvent event) {
        int y = event.getPoint().y;
        y -= PADDING;
        
        int stride = getHeight() / maxDepth;
        int level = Math.min(nodes.size() - 1, Math.max(0, (y + stride / 2) / stride));
        
        double closestDistance = 0.0;
        Node closestNode = null;
        for (Map.Entry<Point, Node> node : nodes.get(level).entrySet()) {
            double distance = node.getKey().distance(event.getPoint());
            if (closestNode == null || distance < closestDistance) {
                closestDistance = distance;
                closestNode = node.getValue();
            }
        }

        return String.valueOf(closestNode != null ? closestNode.getText() : "");
    }
    
    @Override
    public void paint(Graphics graphics) {
        currentGraphics = graphics;
        
        nodes.forEach(Map::clear);
        
        int height = getHeight() - (2 * PADDING) - NODE_SIZE;
        int heightPerLevel = height / (maxDepth - 1);
        verticalGap = heightPerLevel - NODE_SIZE;
        
        graphics.setColor(Color.WHITE);
        graphics.fillRect(PADDING, PADDING, getWidth() - PADDING * 2, getHeight() - PADDING * 2);
        
        draw(root, compareWith, 0, PADDING, getWidth() - PADDING);
    }
    
    private int draw(Node node, Node compareWith, int level, int minX, int maxX) {
        int xSize = maxX - minX;
        int middle = minX + (xSize / 2);
        
        int y = PADDING + level * (verticalGap + NODE_SIZE);
        
        if (node.getType() == Type.SINGLE_STATEMENT) {
            currentGraphics.setColor(Color.MAGENTA);
            currentGraphics.fillOval(middle - NODE_SIZE / 2, y, NODE_SIZE, NODE_SIZE);
        }
        currentGraphics.setColor(Color.BLACK);
        currentGraphics.drawOval(middle - NODE_SIZE / 2, y, NODE_SIZE, NODE_SIZE);
        
        nodes.get(level).put(new Point(middle, y + NODE_SIZE / 2), node);
        
        if (node.childCount() > 0) {
            drawChildren(node, compareWith, level, minX, middle);
        }
        
        return middle;
    }

    private void drawChildren(Node node, Node compareWith, int level, int minX, int middle) {
        int y = PADDING + level * (verticalGap + NODE_SIZE) + NODE_SIZE;
        int xStart = minX;
        
        List<Integer> matchingIndices = null;
        if (compareWith != null) {
            matchingIndices = new ArrayList<>(node.childCount());
            List<Integer> unmatchedIndices = IntStream.range(0, compareWith.childCount())
                    .mapToObj(Integer::valueOf)
                    .collect(Collectors.toList());
            for (Node child : node.childIterator()) {
                int match = compareWith.indexOf(child);
                matchingIndices.add(match);
                if (match != -1) {
                    unmatchedIndices.remove((Integer) match);
                }
            }
            
            for (int unmatchedIndex : unmatchedIndices) {
                Set<Node> nodesOfUnmatched = compareWith.get(unmatchedIndex).stream().collect(Collectors.toSet());
                int maxNodesMatched = 0;
                int bestMatch = -1;
                for (int i = 0; i < matchingIndices.size(); i++) {
                    if (matchingIndices.get(i) == -1) {
                        Set<Node> ourNodes = node.get(i).stream().collect(Collectors.toSet());
                        ourNodes.retainAll(nodesOfUnmatched);
                        if (maxNodesMatched < ourNodes.size()) {
                            maxNodesMatched = ourNodes.size();
                            bestMatch = i;
                        }
                    }
                }
                
                if (bestMatch != -1) {
                    matchingIndices.set(bestMatch, unmatchedIndex);
                }
            }
        }
        
        for (int i = 0; i < node.childCount(); i++) {
            Node child = node.get(i);
            
            Color edgeColor = Color.GRAY;
            Node compareWithChild = null;
            
            if (this.compareWith != null) {
                if (compareWith != null) {
                    if (matchingIndices.get(i) != -1) {
                        compareWithChild = compareWith.get(matchingIndices.get(i));
                    }
                }
                if (child != compareWithChild) {
                    edgeColor = Color.RED;
                }
            }
            
            int childWidth = getWidth(child);
            int childMiddle = draw(child, compareWithChild, level + 1, xStart, xStart + childWidth);
            
            xStart += childWidth + HORIZONTAL_GAP;
            
            currentGraphics.setColor(edgeColor);
            currentGraphics.drawLine(middle, y, childMiddle, y + verticalGap);
        }
    }
    
    private static int getMaxDepth(Node node) {
        int depth = 1;
        if (node.getType() != Type.LEAF) {
            for (Node child : node.childIterator()) {
                int childDepth = getMaxDepth(child);
                if (childDepth > depth) {
                    depth = childDepth;
                }
            }
            depth++;
        }
        return depth;
    }
    
    private static int getWidth(Node node) {
        int width = 0;
        if (node.getType() == Type.LEAF) {
            width = NODE_SIZE;
        } else {
            for (Node child : node.childIterator()) {
                width += getWidth(child);
            }
            width += HORIZONTAL_GAP * (node.childCount() - 1);
        }
        return width;
    }
    
}
