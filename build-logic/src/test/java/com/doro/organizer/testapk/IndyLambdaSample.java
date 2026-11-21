package com.doro.organizer.testapk;

import java.util.function.Supplier;

/**
 * javac always compiles a lambda to an {@code invokedynamic} through {@code LambdaMetafactory} (Kotlin's kotlin-dsl
 * may not), so {@link ClassHeaderTest} reads this class for {@link ClassHeader#getLambdaTypes()}: {@code Supplier} and
 * {@code Runnable}, plus the {@code String} of a string concatenation's {@code invokedynamic}, which
 * {@code TestApkKeepRules.supertypeChains} ignores because it is no interface.
 */
final class IndyLambdaSample {
    private IndyLambdaSample() {}

    static Supplier<String> supplier(String suffix) {
        return () -> "value" + suffix;
    }

    static Runnable runnable() {
        return () -> {};
    }

    static String concatenate(String a, int b) {
        return a + b;
    }
}
