package com.vulnscan.ingest;

/** Decompilation failed or the input layout is not usable. Message is user-facing. */
public class DecompileException extends RuntimeException {
    public DecompileException(String message) {
        super(message);
    }
}
