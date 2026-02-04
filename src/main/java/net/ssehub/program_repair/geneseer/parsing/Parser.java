package net.ssehub.program_repair.geneseer.parsing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import net.ssehub.program_repair.geneseer.parsing.Node.Metadata;
import net.ssehub.program_repair.geneseer.parsing.Node.Type;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaLexer;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.ClassBodyDeclarationContext;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.InterfaceBodyDeclarationContext;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.InterfaceMemberDeclarationContext;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.MemberDeclarationContext;
import net.ssehub.program_repair.geneseer.parsing.antlr.JavaParser.TypeDeclarationContext;

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
        case "TypeDeclarationContext":
            result = Type.TYPE;
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
            result = convertNonTerminal(antlrTree);
        }
        return result;
    }

    private Node convertNonTerminal(ParseTree antlrTree) {
        Node result;
        Type nodeType = getType(antlrTree.getClass().getSimpleName());
        String methodName = null;
        if (antlrTree instanceof ClassBodyDeclarationContext decl) {
            if (decl.memberDeclaration() != null) {
                MemberDeclarationContext memberDecl = decl.memberDeclaration();
                if (memberDecl.methodDeclaration() != null) {
                    nodeType = Type.METHOD;
                    methodName = memberDecl.methodDeclaration().identifier().getText();
                } else if (memberDecl.genericMethodDeclaration() != null) {
                    nodeType = Type.METHOD;
                    methodName = memberDecl.genericMethodDeclaration().methodDeclaration().identifier().getText();
                } else if (memberDecl.constructorDeclaration() != null) {
                    nodeType = Type.CONSTRUCTOR;
                    methodName = memberDecl.constructorDeclaration().identifier().getText();
                } else if (memberDecl.genericConstructorDeclaration() != null) {
                    nodeType = Type.CONSTRUCTOR;
                    methodName = memberDecl.genericConstructorDeclaration().constructorDeclaration()
                            .identifier().getText();
                } else if (memberDecl.fieldDeclaration() != null) {
                    nodeType = Type.ATTRIBUTE;
                }
            }
        } else if (antlrTree instanceof InterfaceBodyDeclarationContext decl) {
            if (decl.interfaceMemberDeclaration() != null) {
                InterfaceMemberDeclarationContext memberDecl = decl.interfaceMemberDeclaration();
                if (memberDecl.interfaceMethodDeclaration() != null) {
                    nodeType = Type.METHOD;
                    methodName = memberDecl.interfaceMethodDeclaration().interfaceCommonBodyDeclaration()
                            .identifier().getText();
                } else if (memberDecl.genericInterfaceMethodDeclaration() != null) {
                    nodeType = Type.METHOD;
                    methodName = memberDecl.genericInterfaceMethodDeclaration().interfaceCommonBodyDeclaration()
                            .identifier().getText();
                }
            }
        }
        
        result = new InnerNode(nodeType);
        for (int i = 0; i < antlrTree.getChildCount(); i++) {
            if ((nodeType == Type.METHOD || nodeType == Type.CONSTRUCTOR)
                    && (antlrTree.getChild(i) instanceof MemberDeclarationContext
                    || antlrTree.getChild(i) instanceof InterfaceMemberDeclarationContext)) {
                // do not convert memberDecl as normal child, but skip directly to nested rule, so that we get a
                // flat method head in the tree
                ((ParserRuleContext) antlrTree.getChild(i)).children.stream()
                        .flatMap(Parser::streamOfChildreen)
                        .forEach(child -> result.add(convert(child)));
                
            } else {
                result.add(convert(antlrTree.getChild(i)));
            }
        }
        
        if (antlrTree instanceof TypeDeclarationContext typeDecl) {
            result.setMetadata(Metadata.TYPE_NAME, getTypeName(typeDecl));
        } else if (nodeType == Type.METHOD || nodeType == Type.CONSTRUCTOR) {
            result.setMetadata(Metadata.METHOD_NAME, methodName);
        }
        return result;
    }

    private String getTypeName(TypeDeclarationContext typeDecl) {
        String typeName;
        if (typeDecl.classDeclaration() != null) {
            typeName = typeDecl.classDeclaration().identifier().getText();
        } else if (typeDecl.enumDeclaration() != null) {
            typeName = typeDecl.enumDeclaration().identifier().getText();
        } else if (typeDecl.interfaceDeclaration() != null) {
            typeName = typeDecl.interfaceDeclaration().identifier().getText();
        } else if (typeDecl.annotationTypeDeclaration() != null) {
            typeName = typeDecl.annotationTypeDeclaration().identifier().getText();
        } else if (typeDecl.recordDeclaration() != null) {
            typeName = typeDecl.recordDeclaration().identifier().getText();
        } else {
            typeName = null;
        }
        return typeName;
    }

    private static Stream<? extends ParseTree> streamOfChildreen(ParseTree child) {
        List<ParseTree> children = new LinkedList<>();
        for (int j = 0; j < child.getChildCount(); j++) {
            children.add(child.getChild(j));
        }
        return children.stream();
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
