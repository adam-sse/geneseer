package net.ssehub.program_repair.geneseer.llm;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class DiffLlmFixer extends AbstractLlmFixer {

    public DiffLlmFixer(IChatGptConnection llm, TemporaryDirectoryManager tempDirManager, Charset encoding,
            Path projectRoot) {
        super(llm, tempDirManager, encoding, projectRoot);
    }
    
    @Override
    protected String getSysteMessage() {
        return "You are an automated program repair tool. Write no explanations and only output a unified diff."
                + " The diff should have no header.";
    }

    @Override
    protected List<String> applyAnswer(String answer, List<String> originalFileContent,
            LineRange submittedRange) throws AnswerDoesNotApplyException {
        
        List<String> diffLines = Arrays.asList(answer.split("\n"));
        List<String> patchedFile = new LinkedList<>();
        
        int originalFileContentIndex = 0;
        for (String diffLine : diffLines) {
            if (diffLine.startsWith("@@") || diffLine.startsWith("+++") || diffLine.startsWith("---")) {
                continue;
            }
            
            if (diffLine.startsWith("+")) {
                patchedFile.add(diffLine.substring(1));
                
            } else if (diffLine.startsWith("-")) {
                diffLine = diffLine.substring(1).trim();
                String currentFileLine = originalFileContent.get(originalFileContentIndex).trim();
                while (currentFileLine.isEmpty() && !diffLine.equalsIgnoreCase(diffLine)) {
                    patchedFile.add(originalFileContent.get(originalFileContentIndex));
                    originalFileContentIndex++;
                }
                
                if (!diffLine.equalsIgnoreCase(currentFileLine)) {
                    throw new AnswerDoesNotApplyException("Removed line from diff \"" + diffLine
                            + "\" does not match with file content \"" + currentFileLine + "\"");
                } else {
                    originalFileContentIndex++;
                }
                
            } else {
                diffLine = diffLine.trim().toLowerCase();
                String currentFileLine = originalFileContent.get(originalFileContentIndex).trim();
                while (!currentFileLine.equalsIgnoreCase(diffLine)) {
                    patchedFile.add(originalFileContent.get(originalFileContentIndex));
                    originalFileContentIndex++;
                    if (originalFileContentIndex < originalFileContent.size()) {
                        currentFileLine = originalFileContent.get(originalFileContentIndex).trim();
                    } else {
                        break;
                    }
                }
                
                if (originalFileContentIndex >= originalFileContent.size()) {
                    throw new AnswerDoesNotApplyException("Can't find diff line in original file: " + diffLine);
                }
                
                patchedFile.add(originalFileContent.get(originalFileContentIndex));
                originalFileContentIndex++;
            }
        }
        
        while (originalFileContentIndex < originalFileContent.size()) {
            patchedFile.add(originalFileContent.get(originalFileContentIndex));
            originalFileContentIndex++;
        }
        
        return patchedFile;
    }
    
}
