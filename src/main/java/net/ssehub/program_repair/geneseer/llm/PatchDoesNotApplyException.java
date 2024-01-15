package net.ssehub.program_repair.geneseer.llm;


public class PatchDoesNotApplyException extends Exception {

    private static final long serialVersionUID = -7304181087872893564L;

    public PatchDoesNotApplyException() {
        super();
    }

    public PatchDoesNotApplyException(String message) {
        super(message);
    }

}
