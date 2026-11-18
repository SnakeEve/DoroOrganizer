# Release, qa and benchmark builds, on top of proguard-android-optimize.txt (DEC-13).

# DEC-70: remove every android.util.Log call (levels up to ASSERT, so also Log.wtf and Log.println).
-maximumremovedandroidloglevel 7
