package net.ssehub.program_repair.geneseer.mutations;

import java.io.File;
import java.util.List;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtStatement;
import spoon.reflect.cu.SourcePosition;

public class RemoveStatementMutation implements IMutation {

    private CtStatement toRemove;
    
    public RemoveStatementMutation(CtStatement toRemove) {
        this.toRemove = toRemove;
    }
    
    @Override
    public void apply(CtModel model) throws MutationException {
        List<CtStatement> matches = model.getElements(e -> {
                if (e.equals(toRemove)) {
                    SourcePosition p1 = e.getPosition();
                    SourcePosition p2 = toRemove.getPosition();
                    
                    File f1 = p1.getFile();
                    File f2 = p2.getFile();
                    
                    if (f1 != null && f2 != null) {
                        return f1.getName().equals(f2.getName()) && p1.getLine() == p2.getLine();
                    } else {
                        return f1 == f2 && p1.getLine() == p2.getLine();
                    }
                } else {
                    return false;
                }
            });
        if (matches.size() == 0) {
            throw new MutationException("Couldn't find statement to remove (" + describeTarget() + ")");
        } else if (matches.size() > 1) {
            throw new MutationException("Found multiple instances of statement to remove (" + describeTarget() + ")");
        } else {
            matches.get(0).delete();
        }
    }
    
    private String describeTarget() {
        SourcePosition position =  toRemove.getPosition();
        return toRemove.getClass().getSimpleName() + " at "
                + position.getFile().getName() + ":" + position.getLine();
    }

    @Override
    public String textDescription() {
        return "Remove " + describeTarget();
    }

}
