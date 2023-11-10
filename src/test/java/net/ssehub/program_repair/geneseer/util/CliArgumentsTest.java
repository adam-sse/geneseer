package net.ssehub.program_repair.geneseer.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

public class CliArgumentsTest {

    @Test
    public void allowedOptionWithoutDashes_throws() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CliArguments(new String[0], Set.of("nodash")));
        assertEquals("Options must start with --", e.getMessage());
    }
    
    @Test
    public void missingValueAfterOption_throws() {
        String[] args = {"--option"};
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CliArguments(args, Set.of("--option")));
        
        assertEquals("Missing value after --option", e.getMessage());
    }
    
    @Test
    public void unknownOption_throws() {
        String[] args = {"--option"};
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CliArguments(args, Set.of("--other-option")));
        
        assertEquals("Unknown command line option: --option", e.getMessage());
    }
    
    @Test
    public void presentOption_isReturned() {
        String[] args = {"--option", "some value"};
        CliArguments cli = new CliArguments(args, Set.of("--option"));
        
        assertAll(
            () -> assertEquals("some value", cli.getOption("--option")),
            () -> assertEquals("some value", cli.getOption("--option", "default value")),
            () -> assertEquals("some value", cli.getOptionOrThrow("--option")),
            () -> assertTrue(cli.hasOption("--option"))
        );
    }
    
    @Test
    public void notPresentOption_isReturned() {
        CliArguments cli = new CliArguments(new String[0], Set.of("--option"));
        
        assertAll(
            () -> assertNull(cli.getOption("--option")),
            () -> assertEquals("default value", cli.getOption("--option", "default value")),
            () -> {
                IllegalArgumentException e =  assertThrows(IllegalArgumentException.class,
                        () -> cli.getOptionOrThrow("--option"));
                assertEquals("Missing required command line option --option", e.getMessage());
            },
            () -> assertFalse(cli.hasOption("--option"))
        );
    }

    @Test
    public void multipleOptions_areReturned() {
        String[] args = {"--option", "some value", "--other-option", "other value"};
        CliArguments cli = new CliArguments(args, Set.of("--option", "--other-option"));
        
        assertAll(
            () -> assertEquals("some value", cli.getOption("--option")),
            () -> assertTrue(cli.hasOption("--option")),
            () -> assertEquals("other value", cli.getOption("--other-option")),
            () -> assertTrue(cli.hasOption("--other-option"))
        );
    }
    
    @Test
    public void repeatetOption_isOverwritten() {
        String[] args = {"--option", "some value", "--option", "other value"};
        CliArguments cli = new CliArguments(args, Set.of("--option"));
        
        assertEquals("other value", cli.getOption("--option"));
    }
    
    @Test
    public void noRemainingArguments_emptyListReturned() {
        String[] args = {"--option", "some value"};
        CliArguments cli = new CliArguments(args, Set.of("--option"));
        
        assertEquals(Collections.emptyList(), cli.getRemaining());
    }
    
    @Test
    public void argumentsAfterOptions_returnedAsList() {
        String[] args = {"--option", "some value", "arg 1", "another arg"};
        CliArguments cli = new CliArguments(args, Set.of("--option"));
        
        assertEquals(List.of("arg 1", "another arg"), cli.getRemaining());
    }
    
    @Test
    public void doubleDash_returnsRemainingInsteadOfOption() {
        String[] args = {"--", "--option", "some value", "arg 1", "another arg"};
        CliArguments cli = new CliArguments(args, Set.of("--option"));
        
        assertAll(
            () -> assertEquals(List.of("--option", "some value", "arg 1", "another arg"), cli.getRemaining()),
            () -> assertFalse(cli.hasOption("--option")),
            () -> assertNull(cli.getOption("--option"))
        );
        
    }
    
}
