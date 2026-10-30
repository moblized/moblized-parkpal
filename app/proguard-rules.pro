# ParkPal release build rules.

# Keep class and method names so crash reports stay readable. Shrinking and optimizing still apply.
-dontobfuscate

# Some libraries reference optional classes that aren't in the app; don't fail the build over them.
-ignorewarnings

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Map library and Android Auto models are used through reflection in places.
-keep class org.osmdroid.** { *; }
-keep class androidx.car.app.** { *; }
