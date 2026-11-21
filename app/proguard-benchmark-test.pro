# The androidTest APK of the benchmark build (DEC-12). AGP runs R8 on it because the tested build type is minified.
# JUnit, the runner and the test rules find the test classes by reflection, so the test APK is only dexed: nothing in
# it is removed, optimized or renamed. The measured code is the app APK's, which proguard-benchmark.pro configures.
-dontshrink
-dontoptimize
-dontobfuscate

# Error Prone's annotations (from Guava, through the test libraries) name a javax.lang.model type that Android lacks.
-dontwarn javax.lang.model.element.Modifier
