package net.ssehub.program_repair.geneseer.llm;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.logging.Logger;

import net.ssehub.program_repair.geneseer.llm.ChatGptMessage.Role;
import net.ssehub.program_repair.geneseer.llm.ChatGptResponse.FinishReason;
import net.ssehub.program_repair.geneseer.llm.ChatGptResponse.Usage;

public class DummyChatGptConnection implements IChatGptConnection {

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
    
    private static int id = 0;
    
    @Override
    public ChatGptResponse send(ChatGptRequest request) throws IOException {
        LOG.fine(() -> "Got request: " + request);
        
        return new ChatGptResponse(
                String.format("dummy-%04d", id++),
                List.of(new ChatGptResponse.Choice(
                        FinishReason.STOP, 0, new ChatGptMessage(TEXT, Role.ASSISTANT), null)),
                ZonedDateTime.now().toEpochSecond(),
                request.getModel(),
                "dummy",
                "chat.completion",
                new Usage(0, 0, 0));
    }
    
}
