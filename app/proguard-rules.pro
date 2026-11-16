# AbhayHealth ProGuard / R8 Optimization Rules

# Preserve Room entities and DAOs
-keepclassmembers class * {
    @androidx.room.Entity *;
    @androidx.room.Dao *;
}
-keep class com.example.abhayhealth.feature.records.** { *; }
-keep class com.example.abhayhealth.feature.medicines.** { *; }
-keep class com.example.abhayhealth.feature.symptoms.** { *; }
-keep class com.example.abhayhealth.feature.vitals.** { *; }
-keep class com.example.abhayhealth.feature.assistant.** { *; }

# Preserve Data Models for Navigation and Preferences
-keep class com.example.abhayhealth.domain.model.** { *; }

# Keep Firebase Auth classes
-keep class com.google.firebase.auth.** { *; }

# Also keep ML Kit text recognition
-keep class com.google.mlkit.vision.text.** { *; }
-dontwarn com.google.mlkit.**
