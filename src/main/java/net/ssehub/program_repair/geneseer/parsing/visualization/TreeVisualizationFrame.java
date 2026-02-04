package net.ssehub.program_repair.geneseer.parsing.visualization;

import java.awt.BorderLayout;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JScrollPane;

import net.ssehub.program_repair.geneseer.parsing.Node;

public class TreeVisualizationFrame extends JFrame {

    private static final long serialVersionUID = 4294427171296768764L;
    
    public TreeVisualizationFrame(Node root) {
        this(root, null);
    }
    
    public TreeVisualizationFrame(Node root1, Node root2) {
        
        if (root2 != null) {
            getContentPane().setLayout(new BoxLayout(getContentPane(), BoxLayout.X_AXIS));
            JScrollPane pane1 = new JScrollPane(new TreeVisualization(root1, root2));
            add(pane1);
            JScrollPane pane2 = new JScrollPane(new TreeVisualization(root2, root1));
            add(pane2);
        } else {
            setLayout(new BorderLayout());
            JScrollPane pane = new JScrollPane(new TreeVisualization(root1, null));
            add(pane);
        }
        
        setTitle("Geneseer Tree Visualization");
        pack();
        setSize(Math.min(1600, getWidth()), Math.min(1000, getHeight()));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setVisible(true);
    }

}
