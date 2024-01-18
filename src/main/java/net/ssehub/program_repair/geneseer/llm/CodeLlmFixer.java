package net.ssehub.program_repair.geneseer.llm;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.ssehub.program_repair.geneseer.util.TemporaryDirectoryManager;

public class CodeLlmFixer extends AbstractLlmFixer {

    public CodeLlmFixer(IChatGptConnection llm, TemporaryDirectoryManager tempDirManager, Charset encoding,
            Path projectRoot) {
        super(llm, tempDirManager, encoding, projectRoot);
    }

    @Override
    protected String getSysteMessage() {
        return "You are an automated program repair tool. Write no explanations and only output the fixed code."
                + " Output the complete (fixed) code that is given to you, even if only a small part of it is changed.";
    }

    @Override
    protected List<String> applyAnswer(String answer, List<String> originalFileContent,
            LineRange submittedRange) throws AnswerDoesNotApplyException {
        
        String[] answerLines = answer.split("\n");
        
        List<String> newFileContent = new ArrayList<>(
                originalFileContent.size() - submittedRange.size() + answerLines.length);
        
        int startIndex = submittedRange.start() - 1;
        int endIndex = submittedRange.end() - 1;
        
        for (int i = 0; i < startIndex; i++) {
            newFileContent.add(originalFileContent.get(i));
        }
        
        for (int i = 0; i < answerLines.length; i++) {
            newFileContent.add(answerLines[i]);
        }
        
        for (int i = endIndex + 1; i < originalFileContent.size(); i++) {
            newFileContent.add(originalFileContent.get(i));
        }
        
        return newFileContent;
    }

}
