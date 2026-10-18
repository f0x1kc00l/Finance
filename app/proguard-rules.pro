# ── FinanceApp R8 rules ─────────────────────────────────────────────────────
# AndroidX, Hilt, Room, Compose, Biometric and Play Services ship their own consumer rules,
# so no blanket -keep is needed (v1.0 kept all of androidx.compose.runtime / biometric /
# security-crypto, which only bloated the APK).

# Enum constant NAMES are persisted (Room columns, DataStore, saved state, nav arguments).
# Keep them stable so an R8 update can never change what valueOf()/name() see.
-keepclassmembernames enum com.financeapp.** { <fields>; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Strip verbose logging from release builds (no financial data is logged anyway).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
