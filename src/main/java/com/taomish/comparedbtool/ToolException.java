package com.taomish.comparedbtool;

/** User-facing errors must not contain connection passwords or raw JDBC exceptions. */
public class ToolException extends RuntimeException {
    public ToolException(String message) { super(message); }
}
