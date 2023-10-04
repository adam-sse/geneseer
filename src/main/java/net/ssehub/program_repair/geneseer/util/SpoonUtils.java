package net.ssehub.program_repair.geneseer.util;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtDo;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtForEach;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtWhile;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtPath;
import spoon.reflect.path.CtRole;

public class SpoonUtils {
    
    private SpoonUtils() {}
    
    public static String statementToStringWithLocation(CtStatement statement) {
        SourcePosition pos = statement.getPosition();
        String filename;
        if (pos.getFile() != null) {
            filename = pos.getFile().getName() + ":" + pos.getLine();
        } else {
            filename = "<null>";
        }
        return filename + " " + statement.getClass().getSimpleName() + " "
                + statement.toString().replaceAll("[\n\r]", "[\\\\n]");
    }
    
    public static String statementToStringWithLocation(CtStatement statement, Path baseSourceDir) {
        SourcePosition pos = statement.getPosition();
        String filename;
        if (pos.getFile() != null) {
            filename = baseSourceDir.relativize(pos.getFile().toPath()).toString() + ":" + pos.getLine();
        } else {
            filename = "<null>";
        }
        return filename + " " + statement.getClass().getSimpleName() + " "
            + statement.toString().replaceAll("[\n\r]", "[\\\\n]");
    }
    
    public static CtElement resolvePath(CtModel model, CtPath path) throws NoSuchElementException {
        List<CtStatement> elements = path.evaluateOn(model.getRootPackage());
        if (elements.size() == 0) {
            throw new NoSuchElementException("Found no element for path " + path);
        } else if (elements.size() > 1) {
            throw new NoSuchElementException("Found more than element for path " + path);
        } else {
            return elements.get(0);
        }
    }
    
    public static boolean isSingleStatement(CtStatement statement) {
        return statement.getRoleInParent() == CtRole.STATEMENT
                && !(statement instanceof CtFor)
                && !(statement instanceof CtForEach)
                && !(statement instanceof CtWhile)
                && !(statement instanceof CtIf)
                && !(statement instanceof CtDo)
                && !(statement instanceof CtBlock);
    }
    
    public static boolean isStatement(CtStatement statement) {
        return statement.getRoleInParent() == CtRole.STATEMENT;
    }

}
