package com.vulnscan.planning;

/** The plan cannot be made with the configured limits. Message is user-facing and names the numbers. */
public class PlanningException extends RuntimeException {
    public PlanningException(String message) {
        super(message);
    }
}
