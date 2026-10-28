package com.taomish.comparedbtool;

/** Keep user-facing messages concise; preserve causes for redacted diagnostic logging. */
public class ToolException extends RuntimeException {
    public ToolException(String message) { super(message); }
    public ToolException(String message, Throwable cause) { super(message, cause); }
}
