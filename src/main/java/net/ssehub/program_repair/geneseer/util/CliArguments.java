package net.ssehub.program_repair.geneseer.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CliArguments {

    private Map<String, String> options;
    
    private List<String> remaining;
    
    public CliArguments(String[] args, Set<String> allowedOptions) {
        for (String allowedOption : allowedOptions) {
            if (!allowedOption.startsWith("--")) {
                throw new IllegalArgumentException("Options must start with --");
            }
        }
        
        options = new HashMap<>();
        
        int argsIndex = 0;
        boolean terminated = false;
        while (!terminated && argsIndex < args.length && args[argsIndex].startsWith("--")) {
            String option = args[argsIndex];
            
            if (option.equals("--")) {
                argsIndex++;
                terminated = true;
                
            } else if (allowedOptions.contains(option)) {
                if (argsIndex + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value after " + option);
                }
                options.put(option, args[argsIndex + 1]);
                argsIndex += 2;
                
            } else {
                throw new IllegalArgumentException("Unknown command line option: " + option);
            }
        }
        
        remaining = new ArrayList<>(args.length - argsIndex);
        for (; argsIndex < args.length; argsIndex++) {
            remaining.add(args[argsIndex]);
        }
        remaining = Collections.unmodifiableList(remaining);
    }
    
    public boolean hasOption(String option) {
        return options.containsKey(option);
    }
    
    public String getOption(String option) {
        return options.get(option);
    }
    
    public String getOption(String option, String defaultValue) {
        return options.getOrDefault(option, defaultValue);
    }
    
    public String getOptionOrThrow(String option) throws IllegalArgumentException {
        String value = getOption(option);
        if (value == null) {
            throw new IllegalArgumentException("Missing required command line option " + option);
        }
        return value;
    }
    
    public List<String> getRemaining() {
        return remaining;
    }
    
}
