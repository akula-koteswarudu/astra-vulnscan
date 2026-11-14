package com.vulnscan.config;

/** A configuration file is missing, malformed or asks for something not built yet. Message is user-facing. */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
