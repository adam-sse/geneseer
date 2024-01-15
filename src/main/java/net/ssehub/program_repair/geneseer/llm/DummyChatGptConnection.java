package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.util.List;

import net.ssehub.program_repair.geneseer.llm.ChatGptResponse.Usage;

public class DummyChatGptConnection extends ChatGptConnection {

    @Override
    public void login() throws IOException {
    }
    
    @Override
    public void logout() throws IOException {
    }
    
    @Override
    public ChatGptResponse send(ChatGptRequest request) throws IOException {
        return new ChatGptResponse("", "", 0, request.getModel(),
                List.of(new ChatGptResponse.Choice(0, new ChatGptMessage("dummy message", "assistant"), null, "stop")),
                new Usage(0, 0, 0), "");
    }
    
}
