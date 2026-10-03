# Bouncy Castle: we use the lightweight (Bc*) API, never the JCA provider, but the jar
# references provider / JCA classes that don't exist on Android.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.lang.invoke.**
# mime4j
-dontwarn org.apache.james.**
-dontwarn org.apache.commons.**
