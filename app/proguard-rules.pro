# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# Keep libsu classes
-keep class com.topjohnwu.superuser.** { *; }

# Keep data classes for DataStore
-keep class com.example.piholemonitor.data.** { *; }
-keep class com.example.piholemonitor.domain.** { *; }
