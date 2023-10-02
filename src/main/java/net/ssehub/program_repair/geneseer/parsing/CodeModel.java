package net.ssehub.program_repair.geneseer.parsing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Stack;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Position;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.util.FileUtils;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class CodeModel {

    private Node parseTree;
    
    private Path sourceDirectory;
    
    public CodeModel(Path sourceDirectory) throws IOException {
        this.sourceDirectory = sourceDirectory;
        
        try (Probe probe = Measurement.INSTANCE.start("parsing")) {
            
            parseTree = new InnerNode(Type.OTHER);
            
            try {
                Files.walk(sourceDirectory)
                        .filter(f -> f.getFileName().toString().endsWith(".java"))
                        .forEach(f -> {
                            try {
                                Node file = parseFile(f);
                                fix(file);
                                file.setMetadata(Metadata.FILENAME, sourceDirectory.relativize(f));
                                parseTree.children().add(file);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }
    
    private Node parseFile(Path file) throws IOException {
        Java8Lexer lexer = new Java8Lexer(CharStreams.fromFileName(file.toString(), Configuration.INSTANCE.getEncoding()));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        Java8Parser parser = new Java8Parser(tokens);
        ParseTree antlrTree = parser.compilationUnit();
        return convert(antlrTree);
    }
    
    private static Type getType(String typeName) {
        switch (typeName) {
        
        case "ReturnStatementContext":
        case "ExpressionStatementContext":
        case "LocalVariableDeclarationStatementContext":
        case "BreakStatementContext":
        case "ContinueStatementContext":
        case "ThrowStatementContext":
        case "AssertStatementContext":
        case "EmptyStatement_Context":
            return Type.SINGLE_STATEMENT;
            
        case "BlockContext":
        case "IfThenStatementContext":
        case "IfThenElseStatementContext":
        case "SwichStatementContext":
        case "ForStatementContext":
        case "TryStatementContext":
        case "TryWithResourcesStatementContext":
        case "SynchronizedStatementContext":
            return Type.COMPOSIT_STATEMENT;
        
        default:
            return Type.OTHER;
        }
    }
    
    private Node convert(ParseTree antlrTree) {
        Node result;
        if (antlrTree instanceof TerminalNode terminal) {
            Token token = terminal.getSymbol();
            result = new LeafNode(terminal.getText(), new Position(token.getLine(), token.getCharPositionInLine()));
        } else {
            result = new InnerNode(getType(antlrTree.getClass().getSimpleName()));
            for (int i = 0; i < antlrTree.getChildCount(); i++) {
                result.children().add(convert(antlrTree.getChild(i)));
            }
        }
        return result;
    }
    
    private void fix(Node compilationUnit) {
        removeEof(compilationUnit);
        removeOneChildCascade(compilationUnit);
    }
    
    private void removeEof(Node compilationUnit) {
        if (compilationUnit.children().get(compilationUnit.children().size() - 1).getText().equals("<EOF>")) {
            compilationUnit.children().remove(compilationUnit.children().size() - 1);
        }
    }
    
    private void removeOneChildCascade(Node node) {
        for (int i = 0; i < node.children().size(); i++) {
            Node child = node.children().get(i);
            if (child.children().size() == 1) {
                Node childChild = child.children().get(0);
                
                Type childType = child.getType();
                Type childChildType = child.children().get(0).getType();
                if (childType == Type.OTHER || childChildType == Type.OTHER) {
                    if (childChildType == Type.OTHER) {
                        childChild.setType(childType);
                    }
                    node.children().set(i, childChild);
                    i--;
                }
            }
        }
        
        for (Node child : node.children()) {
            removeOneChildCascade(child);
        }
    }
    
    public Node getParseTree() {
        return parseTree;
    }
    
    public void write(Path outputDirectory) throws IOException {
        try (Probe probe = Measurement.INSTANCE.start("code-writing")) {
            
            for (Node compilationUnit : parseTree.children()) {
                Path file = outputDirectory.resolve((Path) compilationUnit.getMetadata(Metadata.FILENAME));
                Files.createDirectories(file.getParent());
                
                Files.writeString(file, toText(compilationUnit), Configuration.INSTANCE.getEncoding());
            }
            
            FileUtils.copyAllNonJavaSourceFiles(sourceDirectory, outputDirectory);
        }
    }
    
    private static String toText(Node root) {
        int currentLine = 1;
        int currentColumn = 0;
        
        Stack<Node> nodes = new Stack<>();
        nodes.push(root);
        
        StringBuilder str = new StringBuilder();
        
        while (!nodes.isEmpty()) {
            Node currentNode = nodes.pop();
            
            if (currentNode instanceof LeafNode leaf) {
                if (leaf.getOriginalPosition() != null) {
                    while (leaf.getOriginalPosition().line() > currentLine) {
                        str.append('\n');
                        currentLine++;
                        currentColumn = 0;
                    }
                    while (leaf.getOriginalPosition().column() > currentColumn) {
                        str.append(' ');
                        currentColumn++;
                    }
                    
                } else {
                    str.append(' ');
                }
                
                String text = leaf.getText();
                currentColumn += text.length();
                str.append(text);
                
            } else {
                for (int i = currentNode.children().size() - 1; i >= 0; i--) {
                    nodes.push(currentNode.children().get(i));
                }
            }
        }
        
        return str.toString();
    }
    
}
