package gov.uddp.core.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Test provider: answers from a function and records every request so tests can prove what was sent. */
public class FakeAiDraftService implements AiDraftService {
    public record Call(String instruction, String content) {
    }

    private final BiFunction<String, String, String> answer;
    public final List<Call> calls = new ArrayList<>();

    public FakeAiDraftService(BiFunction<String, String, String> answer) {
        this.answer = answer;
    }

    public static FakeAiDraftService answering(String text) {
        return new FakeAiDraftService((instruction, content) -> text);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String model() {
        return "fake-model";
    }

    @Override
    public String complete(String instruction, String content) {
        calls.add(new Call(instruction, content));
        return answer.apply(instruction, content);
    }
}
