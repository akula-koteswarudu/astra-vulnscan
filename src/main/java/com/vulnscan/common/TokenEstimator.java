package com.vulnscan.common;

/** Token estimate = chars / charsPerToken * (1 + margin), rounded up. Deliberately simple and conservative. */
public record TokenEstimator(int charsPerToken, double margin) {

    public int estimate(int chars) {
        return (int) Math.ceil(chars / (double) charsPerToken * (1 + margin));
    }

    public int estimate(String text) {
        return text == null ? 0 : estimate(text.length());
    }
}
