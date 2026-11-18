# Release, qa and benchmark builds, on top of proguard-android-optimize.txt (DEC-13).

# DEC-70: remove every android.util.Log call (levels up to ASSERT, so also Log.wtf and Log.println).
-maximumremovedandroidloglevel 7

# DEC-70 requires every Log call gone, but the R8 of AGP 9.5.1 applies -maximumremovedandroidloglevel only partially
# (the security.md § 10 "Verify at setup" check failed: calls in androidx, kotlin and SQLCipher classes survived), so the
# Log methods are also declared side-effect free. The release-logs gate (:app:checkReleaseLogs) checks the dex.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
