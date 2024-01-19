package net.ssehub.program_repair.geneseer.llm;


record ChatGptErrorResponse(ErrorContent error) {

    record ErrorContent(String message, String type, String code) {
    }
    
}
