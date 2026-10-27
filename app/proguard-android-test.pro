# JUnit's desktop CPU-time/DisableOnDebug helpers reference JDK management APIs.
# Android does not provide them; instrumentation does not use those optional helpers.
# Scoped to the test APK only. Do not retain or suppress production application code.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn java.lang.management.ThreadMXBean
