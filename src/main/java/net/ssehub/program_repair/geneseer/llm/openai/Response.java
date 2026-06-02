package net.ssehub.program_repair.geneseer.llm.openai;

import java.util.List;

import net.ssehub.program_repair.geneseer.llm.IResponse;
import net.ssehub.program_repair.geneseer.llm.Message;

record Response(List<Message> messages, int queryTokens, int answerTokens) implements IResponse {

    @Override
    public List<Message> getMessages() {
        return messages;
    }

    @Override
    public int getQueryTokens() {
        return queryTokens;
    }

    @Override
    public int getAnswerTokens() {
        return answerTokens;
    }

}
