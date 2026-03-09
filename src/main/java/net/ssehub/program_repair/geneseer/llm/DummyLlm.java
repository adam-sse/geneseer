package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.llm.openai.OpenaiLlm;

public class DummyLlm implements ILlm {

    private static final Logger LOG = Logger.getLogger(OpenaiLlm.class.getName());
    
    private static final String TEXT = """
Code snippet number 1:
```java
    private static void appendOption(final StringBuffer buff,
                                     final Option option,
                                     final boolean required)
    {
        if (!required)
        {
            buff.append("[");
        }

        if (option.getOpt() != null)
        {
            buff.append("-").append(option.getOpt());
        }
        else
        {
            buff.append("--").append(option.getLongOpt());
        }


        if (option.hasArg() && option.hasArgName())
        {
            buff.append(" <").append(option.getArgName()).append(">");
        }


        if (!required)
        {
            buff.append("]");
        }
    }
```
            """;
    
    public static class DummyResponse implements IResponse {

        private List<Message> messages;
        
        public DummyResponse(List<Message> messages) {
            this.messages = messages;
        }
        
        @Override
        public List<Message> getMessages() {
            return messages;
        }
        
        @Override
        public int getQueryTokens() {
            return 0;
        }
        
        @Override
        public int getAnswerTokens() {
            return 0;
        }
        
    }
    
    @Override
    public DummyResponse send(Query query) throws IOException {
        LOG.fine(() -> "Got query: " + query);
        
        return new DummyResponse(List.of(new Message(Role.ASSISTANT, TEXT)));
    }
    
}
