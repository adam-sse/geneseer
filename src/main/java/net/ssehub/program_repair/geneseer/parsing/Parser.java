package net.ssehub.program_repair.geneseer.parsing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import net.ssehub.program_repair.geneseer.Configuration;
import net.ssehub.program_repair.geneseer.parsing.antlr.Java8Lexer;
import net.ssehub.program_repair.geneseer.parsing.antlr.Java8Parser;
import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;
import net.ssehub.program_repair.geneseer.parsing.model.Position;
import net.ssehub.program_repair.geneseer.util.Measurement;
import net.ssehub.program_repair.geneseer.util.Measurement.Probe;

public class Parser {
    
    private static final Logger LOG = Logger.getLogger(Parser.class.getName());

    private Parser() {
    }
    
    public static Node parse(Path sourceDirectory) throws IOException {
        try (Probe probe = Measurement.INSTANCE.start("parsing")) {
            
            Node parseTree = new InnerNode(Type.OTHER);
            
            try {
                Files.walk(sourceDirectory)
                        .filter(f -> f.getFileName().toString().endsWith(".java"))
                        .forEach(f -> {
                            try {
                                Node file = parseFile(f);
                                fix(file);
                                file.setMetadata(Metadata.FILENAME, sourceDirectory.relativize(f));
                                parseTree.add(file);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            
            return parseTree;
        }
    }
    
    private static Node parseFile(Path file) throws IOException {
        Java8Lexer lexer = new Java8Lexer(CharStreams.fromFileName(file.toString(), Configuration.INSTANCE.getEncoding()));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        Java8Parser parser = new Java8Parser(tokens);
        parser.removeErrorListeners(); // remove the default console listener
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                LOG.warning(() -> file + ":" + line + ":" + charPositionInLine + " " + msg);
            }
        });
        ParseTree antlrTree = parser.compilationUnit();
        return convert(antlrTree);
    }
    
    private static Type getType(String typeName) {
        switch (typeName) {
        
        case "CompilationUnitContext":
            return Type.COMPILATION_UNIT;
        
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
    
    private static Node convert(ParseTree antlrTree) {
        Node result;
        if (antlrTree instanceof TerminalNode terminal) {
            Token token = terminal.getSymbol();
            result = new LeafNode(terminal.getText(), new Position(token.getLine(), token.getCharPositionInLine()));
        } else {
            result = new InnerNode(getType(antlrTree.getClass().getSimpleName()));
            for (int i = 0; i < antlrTree.getChildCount(); i++) {
                result.add(convert(antlrTree.getChild(i)));
            }
        }
        return result;
    }
    
    private static void fix(Node compilationUnit) {
        removeEof(compilationUnit);
        removeOneChildCascade(compilationUnit);
    }
    
    private static void removeEof(Node compilationUnit) {
        if (compilationUnit.get(compilationUnit.childCount() - 1).getText().equals("<EOF>")) {
            compilationUnit.remove(compilationUnit.childCount() - 1);
        }
    }
    
    private static void removeOneChildCascade(Node node) {
        for (int i = 0; i < node.childCount(); i++) {
            Node child = node.get(i);
            if (child.childCount() == 1) {
                Node childChild = child.get(0);
                
                Type childType = child.getType();
                Type childChildType = child.get(0).getType();
                if (childType == Type.OTHER || childChildType == Type.OTHER) {
                    if (childChildType == Type.OTHER) {
                        childChild.setType(childType);
                    }
                    node.set(i, childChild);
                    i--;
                }
            }
        }
        
        for (Node child : node.childIterator()) {
            removeOneChildCascade(child);
        }
    }
    
}
