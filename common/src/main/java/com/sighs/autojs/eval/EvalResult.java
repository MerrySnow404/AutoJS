package com.sighs.autojs.eval;

import com.sighs.autojs.side.EvalSide;

import java.util.List;

/**
 * Outcome of one script evaluation.
 *
 * <p>{@code diagnostics} carries anything the side logged while running the snippet, which matters
 * because some failures are not raised back to the caller at all: KubeJS, for example, only writes a
 * refused event registration to its own console. Without these lines an agent would see a successful
 * call and conclude the snippet worked.
 */
public record EvalResult(boolean ok, EvalSide side, String valueType, String value, String error,
                         List<String> stack, List<String> diagnostics, long elapsedMs) {

    public static EvalResult ok(EvalSide side, String valueType, String value, long elapsedMs) {
        return ok(side, valueType, value, List.of(), elapsedMs);
    }

    public static EvalResult ok(EvalSide side, String valueType, String value, List<String> diagnostics, long elapsedMs) {
        return new EvalResult(true, side, valueType, value, null, List.of(), List.copyOf(diagnostics), elapsedMs);
    }

    public static EvalResult failure(EvalSide side, String error, List<String> stack, long elapsedMs) {
        return new EvalResult(false, side, null, null, error,
                stack == null ? List.of() : List.copyOf(stack), List.of(), elapsedMs);
    }

    public EvalResult withDiagnostics(List<String> extra) {
        return extra == null || extra.isEmpty()
                ? this
                : new EvalResult(ok, side, valueType, value, error, stack, List.copyOf(extra), elapsedMs);
    }
}
