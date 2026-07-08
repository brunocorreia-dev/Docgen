package com.docgen;

public interface LLMProvider {
    String generateDocumentation(String prompt) throws Exception;

    /** True when prompts leave the local machine. Drives consent checks and warnings. */
    boolean isRemote();

    /** Human-readable destination of prompts, shown in warnings before sending. */
    String describeDestination();
}
