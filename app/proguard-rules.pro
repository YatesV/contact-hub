# Contact Hub R8 rules
# Room entities/DAOs are handled by Room's own rules.
-keep class com.yatesv.contacthub.data.** { *; }
# osmdroid
-dontwarn org.osmdroid.**
-keep class org.osmdroid.** { *; }
# Services/receivers are referenced from the manifest (kept automatically), keep their names for logs
-keepnames class com.yatesv.contacthub.** { *; }
-dontwarn org.slf4j.**
