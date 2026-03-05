package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.llm.LlmMessage.Role;
import net.ssehub.program_repair.geneseer.llm.openai.ChatGptConnection;

public class DummyLlmConnection implements ILlmApiConnection {

    private static final Logger LOG = Logger.getLogger(ChatGptConnection.class.getName());
    
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
    
    public static class DummyResponse implements ILlmResponse {

        private List<LlmMessage> messages;
        
        public DummyResponse(List<LlmMessage> messages) {
            this.messages = messages;
        }
        
        @Override
        public List<LlmMessage> getMessages() {
            return messages;
        }
        
    }
    
    @Override
    public DummyResponse send(LlmQuery query) throws IOException {
        LOG.fine(() -> "Got query: " + query);
        
        return new DummyResponse(List.of(new LlmMessage(Role.ASSISTANT, TEXT)));
    }
    
}
