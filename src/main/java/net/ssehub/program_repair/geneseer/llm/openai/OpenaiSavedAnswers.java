package net.ssehub.program_repair.geneseer.llm.openai;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.google.gson.Gson;

import net.ssehub.program_repair.geneseer.llm.AbstractLlm;
import net.ssehub.program_repair.geneseer.llm.ILlm;
import net.ssehub.program_repair.geneseer.llm.Message;
import net.ssehub.program_repair.geneseer.llm.Query;

public class OpenaiSavedAnswers implements ILlm {

    private Path responseDirectory;
    
    private int nextAnswer;
    
    private Gson gson;
    
    public OpenaiSavedAnswers(Path responseDirectory) {
        this.responseDirectory = responseDirectory;
        this.nextAnswer = 1;
        this.gson = AbstractLlm.createGson();
    }
    
    @Override
    public Response send(Query query) throws IOException {
        Path answerFile = responseDirectory.resolve("answer-" + nextAnswer + ".json");
        if (!Files.isRegularFile(answerFile)) {
            throw new FileNotFoundException(answerFile.getFileName() + " doesn't exist");
        }
        nextAnswer++;
        
        OpenaiResponse response = gson.fromJson(Files.readString(answerFile, StandardCharsets.UTF_8),
                OpenaiResponse.class);
        Message message = new Message(response.choices().get(0).message().role(),
                response.choices().get(0).message().content());
        if (response.choices().get(0).message().reasoningContent() != null) {
            message.setThinking(response.choices().get(0).message().reasoningContent());
        }
        
        int queryTokens = 0;
        int answerTokens = 0;
        if (response.usage() != null) {
            queryTokens = response.usage().promptTokens();
            answerTokens = response.usage().completionTokens();
        }
        
        return new Response(List.of(message), queryTokens, answerTokens);
    }

}
