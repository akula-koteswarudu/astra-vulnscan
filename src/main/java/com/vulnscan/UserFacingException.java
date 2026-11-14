package com.vulnscan;

/** A failure whose message is meant for the user as-is (no stack trace). */
public class UserFacingException extends RuntimeException {
    public UserFacingException(String message) {
        super(message);
    }
}
