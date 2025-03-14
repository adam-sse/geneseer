package net.ssehub.program_repair.geneseer.parsing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
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

import net.ssehub.program_repair.geneseer.parsing.antlr.JavaLexer;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.ClassDeclarationContext;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.ConstructorDeclarationContext;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.MethodDeclarationContext;
import net.ssehub.program_repair.geneseer.parsing.model.InnerNode;
import net.ssehub.program_repair.geneseer.parsing.model.LeafNode;
import net.ssehub.program_repair.geneseer.parsing.model.Node;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.model.Node.Type;

public class Parser {
    
    private static final Logger LOG = Logger.getLogger(Parser.class.getName());

    private Token previousToken;
    
    public Parser() {
    }
    
    public Node parse(Path sourceDirectory, Charset encoding) throws IOException {
        Node parseTree = new InnerNode(Type.OTHER);
        
        try {
            Files.walk(sourceDirectory)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(f -> {
                        Node file = parseFile(f, encoding);
                        fix(file);
                        file.setMetadata(Metadata.FILE_NAME, sourceDirectory.relativize(f));
                        parseTree.add(file);
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        
        return parseTree;
    }
    
    public Node parseSingleFile(Path sourceFile, Charset encoding) throws IOException {
        try {
            Node file = parseFile(sourceFile, encoding);
            fix(file);
            file.setMetadata(Metadata.FILE_NAME, sourceFile.getFileName());
            return file;
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
    
    private Node parseFile(Path file, Charset encoding) throws UncheckedIOException {
        try {
            JavaLexer lexer = new JavaLexer(CharStreams.fromFileName(file.toString(), encoding));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            JavaParser parser = new JavaParser(tokens);
            parser.removeErrorListeners(); // remove the default console listener
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                        int charPositionInLine, String msg, RecognitionException exc) {
                    LOG.warning(() -> file + ":" + line + ":" + charPositionInLine + " " + msg);
                }
            });
            ParseTree antlrTree = parser.compilationUnit();
            previousToken = null;
            return convert(antlrTree);
            
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    private static Type getType(String typeName) {
        Type result;
        
        switch (typeName) {
        case "CompilationUnitContext":
            result = Type.COMPILATION_UNIT;
            break;
        case "ClassDeclarationContext":
            result = Type.CLASS;
            break;
        case "MethodDeclarationContext":
            result = Type.METHOD;
            break;
        case "ConstructorDeclarationContext":
            result = Type.CONSTRUCTOR;
            break;
        case "BlockStatementContext":
            result = Type.STATEMENT;
            break;
        case "BlockContext":
            result = Type.COMPOSIT_STATEMENT;
            break;
        
        default:
            result = Type.OTHER;
            break;
        }
        
        return result;
    }
    
    private Node convert(ParseTree antlrTree) {
        Node result;
        if (antlrTree instanceof TerminalNode terminal) {
            Token token = terminal.getSymbol();
            LeafNode newLeaf = new LeafNode(terminal.getText());
            if (previousToken != null) {
                newLeaf.setPrefixNewlines(token.getLine() - previousToken.getLine());
                if (newLeaf.getPrefixNewlines() == 0) {
                    newLeaf.setPrefixSpaces(token.getCharPositionInLine()
                            - (previousToken.getCharPositionInLine() + previousToken.getText().length()));
                } else {
                    newLeaf.setPrefixSpaces(token.getCharPositionInLine());
                }
            } else {
                newLeaf.setPrefixNewlines(token.getLine() - 1);
                newLeaf.setPrefixSpaces(token.getCharPositionInLine());
            }
            result = newLeaf;
            previousToken = token;
        } else {
            result = new InnerNode(getType(antlrTree.getClass().getSimpleName()));
            for (int i = 0; i < antlrTree.getChildCount(); i++) {
                result.add(convert(antlrTree.getChild(i)));
            }
            
            if (antlrTree instanceof ClassDeclarationContext classDecl) {
                String className = classDecl.identifier().getText();
                result.setMetadata(Metadata.CLASS_NAME, className);
            } else if (antlrTree instanceof MethodDeclarationContext method) {
                String methodName = method.identifier().getText();
                result.setMetadata(Metadata.METHOD_NAME, methodName);
            } else if (antlrTree instanceof ConstructorDeclarationContext constructor) {
                String constructorName = constructor.identifier().getText();
                result.setMetadata(Metadata.CONSTRUCTOR_NAME, constructorName);
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
