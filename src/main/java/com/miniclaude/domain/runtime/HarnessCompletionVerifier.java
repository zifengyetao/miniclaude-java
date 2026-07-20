package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 模型声明完成后的确定性终止验证器。 */
public interface HarnessCompletionVerifier {
    Verification verify(HarnessProfile profile, String finalText, Set<String> successfulTools);

    final class Verification {
        private final boolean accepted;
        private final String code;

        private Verification(boolean accepted, String code) {
            this.accepted = accepted;
            this.code = code == null ? "" : code;
        }

        public static Verification accept() { return new Verification(true, "ACCEPTED"); }
        public static Verification reject(String code) { return new Verification(false, code); }
        public boolean isAccepted() { return accepted; }
        public String getCode() { return code; }
    }

    static Set<String> immutable(Set<String> tools) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                tools == null ? Collections.emptySet() : tools));
    }
}
