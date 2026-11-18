# Benchmark build only (DEC-12): the performance tests address app classes by their source names.
-dontobfuscate

# The benchmark tests run through DoroTestRunner (testing.md § 7, Wiring): TestDoroApplication overrides startApp,
# DoroAppRule sets DoroApplication.container and TestAppContainer overrides the container members. Nothing in the
# app itself does, so without these rules R8 may inline, devirtualize or remove those members.
-keep class com.doro.organizer.DoroApplication { *; }
-keep class com.doro.organizer.AppContainer { *; }
-keep class com.doro.organizer.core.coroutines.AppDispatchers { *; }
