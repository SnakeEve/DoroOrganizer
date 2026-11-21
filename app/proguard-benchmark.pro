# Benchmark build only (DEC-12): the performance tests address app classes by their source names.
-dontobfuscate

# The benchmark tests run through DoroTestRunner and the shared fixtures of testing.md § 7: TestDoroApplication
# overrides startApp, DoroAppRule sets DoroApplication.container, TestAppContainer overrides the container members, the
# fakes implement the production interfaces and the fixtures call production constructors, repositories and DAOs
# directly. R8 sees none of these callers, so without a keep rule it may remove, merge or re-sign what they use.
# The ui package stays optimized as in release, because P-2 and P-6 time it: the perf fixtures reach it only through
# MainActivity (a manifest entry point), test tags and the Announcer that TestAppContainer wraps. The libraries stay
# optimized too, except what the rules that doro.test-apk-keep-rules generates keep in both for the in-process test APK
# (only the perf tests and the fixtures package): the members it references, the types its classes and lambdas extend
# or implement, and the service types it provides (DEC-103 (5)).
-keep class !com.doro.organizer.ui.**, com.doro.organizer.** { *; }
-keep interface com.doro.organizer.ui.common.Announcer { *; }
