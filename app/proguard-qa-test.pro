# The androidTest APK of the qa build (DEC-103). AGP runs R8 on it because the tested build type is minified, and it
# applies the qa app's mapping to every reference from the test APK into the app. No -dontobfuscate here: the qa app
# is obfuscated, and the test APK must follow its mapping. JUnit, the runner and its arguments (the annotation filter,
# the runner builders) find the test APK's own classes by name and by their runtime annotations, so every class of
# the test APK keeps its name, members and annotations, and nothing in it is optimized.
-dontoptimize
-keep class ** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Error Prone's annotations (from Guava, through the test libraries) name a javax.lang.model type that Android lacks.
-dontwarn javax.lang.model.element.Modifier
