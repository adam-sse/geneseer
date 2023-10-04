package net.ssehub.program_repair.geneseer.genetic;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

public class Result {
    
    public enum Type {
        FOUND_FIX,
        GENERATION_LIMIT_REACHED,
        ORIGINAL_UNFIT,
        IO_EXCEPTION,
    }
    
    private Type type;
    
    private IOException exception;
    
    private double originalFitness;
    
    private double maxFitness;
    
    private double bestFitness;
    
    private int generation;

    private Result(Type type, IOException exception, double originalFitness, double maxFitness, double bestFitness,
            int generation) {
        this.type = type;
        this.exception = exception;
        this.originalFitness = originalFitness;
        this.maxFitness = maxFitness;
        this.bestFitness = bestFitness;
        this.generation = generation;
    }
    
    public static Result foundFix(double originalFitness, double maxFitness, int generation) {
        return new Result(Type.FOUND_FIX, null, originalFitness, maxFitness, maxFitness, generation);
    }
    
    public static Result generationLimitReached(int generation, double originalFitness, double maxFitness,
            double bestFitness) {
        
        return new Result(Type.GENERATION_LIMIT_REACHED, null, originalFitness, maxFitness, bestFitness, generation);
    }
    
    public static Result originalUnfit() {
        return new Result(Type.ORIGINAL_UNFIT, null, 0, 0, 0, 0);
    }
    
    public static Result ioException(IOException e) {
        return new Result(Type.IO_EXCEPTION, e, 0, 0, 0, -1);
    }
    
    public static Result ioException(IOException e, int generation) {
        return new Result(Type.IO_EXCEPTION, e, 0, 0, 0, generation);
    }
    
    public String toCsv() {
        String[] parts = {
                type.name(),
                generation != -1 ? Integer.toString(generation) : "",
                "",
                "",
                "",
                ""
        };
        
        switch (type) {
        
        case FOUND_FIX:
        case GENERATION_LIMIT_REACHED:
            parts[2] = Double.toString(originalFitness);
            parts[3] = Double.toString(maxFitness);
            parts[4] = Double.toString(bestFitness);
            break;
            
        case ORIGINAL_UNFIT:
            break;
            
        case IO_EXCEPTION:
            parts[5] = exception.getMessage();
        }
        
        return Arrays.stream(parts).collect(Collectors.joining(";"));
    }

}
