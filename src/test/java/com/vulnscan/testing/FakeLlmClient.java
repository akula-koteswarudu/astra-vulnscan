package com.vulnscan.testing;

import com.vulnscan.models.LlmClient;
import com.vulnscan.models.LlmRequest;
import com.vulnscan.models.LlmResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Scripted LLM: answers with a function of the request and records every request it saw. */
public class FakeLlmClient implements LlmClient {

    public final List<LlmRequest> requests = new ArrayList<>();
    private final Function<LlmRequest, LlmResponse> answer;

    public FakeLlmClient(Function<LlmRequest, LlmResponse> answer) {
        this.answer = answer;
    }

    public static FakeLlmClient constant(String text) {
        return new FakeLlmClient(r -> ok(text));
    }

    public static LlmResponse ok(String text) {
        return new LlmResponse(text, 100, 50, LlmResponse.StopReason.END, null, "fake-model", 1, false, true, 0);
    }

    public static LlmResponse withStop(String text, LlmResponse.StopReason stop) {
        return new LlmResponse(text, 100, 50, stop, stop == LlmResponse.StopReason.REFUSAL ? "cyber" : null, "fake-model", 1, false, true, 0);
    }

    @Override
    public synchronized LlmResponse complete(LlmRequest request) {
        requests.add(request);
        return answer.apply(request);
    }

    public int calls() {
        return requests.size();
    }
}
