package com.vulnscan.testing;

import com.vulnscan.common.Ticker;
import java.util.ArrayList;
import java.util.List;

/** Virtual clock: sleep advances time instantly and is recorded. */
public class FakeTicker implements Ticker {
    public long now = 1_000_000;
    public final List<Long> sleeps = new ArrayList<>();

    @Override
    public long nowMillis() {
        return now;
    }

    @Override
    public void sleep(long millis) {
        sleeps.add(millis);
        now += Math.max(0, millis);
    }
}
