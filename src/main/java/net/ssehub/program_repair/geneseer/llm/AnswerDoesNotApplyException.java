package net.ssehub.program_repair.geneseer.llm;


public class AnswerDoesNotApplyException extends Exception {

    private static final long serialVersionUID = -7304181087872893564L;

    public AnswerDoesNotApplyException() {
        super();
    }

    public AnswerDoesNotApplyException(String message) {
        super(message);
    }

}
