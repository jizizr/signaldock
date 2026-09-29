# AndroidX Test includes Error Prone's compile-time annotations. Their JDK-only
# Modifier enum is never used on Android; keep this suppression test-only.
-dontwarn javax.lang.model.element.Modifier
