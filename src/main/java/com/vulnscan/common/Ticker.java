package com.vulnscan.common;

/** Time source + sleep, injectable so retry and rate-limit logic is tested without real waiting. */
public interface Ticker {

    long nowMillis();

    void sleep(long millis);

    Ticker SYSTEM = new Ticker() {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
    };
}
