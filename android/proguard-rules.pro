# To enable ProGuard in your project, edit project.properties
# to define the proguard.config property as described in that file.
#
# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the ProGuard
# include property in project.properties.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

-verbose

-dontwarn android.support.**

-dontwarn com.badlogic.gdx.backends.android.AndroidFragmentApplication
-dontwarn com.badlogic.gdx.utils.GdxBuild
-dontwarn com.badlogic.gdx.physics.box2d.utils.Box2DBuild
-dontwarn com.badlogic.gdx.jnigen.BuildTarget*
-dontwarn com.badlogic.gdx.graphics.g2d.freetype.FreetypeBuild

# Needed by the gdx-controllers official extension.
-keep class com.badlogic.gdx.controllers.android.AndroidControllers

# Needed by the Box2D official extension.
-keepclassmembers class com.badlogic.gdx.physics.box2d.World {
   boolean contactFilter(long, long);
   boolean getUseDefaultContactFilter();
   void    beginContact(long);
   void    endContact(long);
   void    preSolve(long, long);
   void    postSolve(long, long);
   boolean reportFixture(long);
   float   reportRayFixture(long, float, float, float, float, float);
}

# Needed to prevent obfuscation of a class that would otherwise be discarded:
-keep class com.badlogic.**{
    **[] $VALUES;
     *;
 }

# These are necessary to make mapsforge work with proguard:

-dontwarn com.caverock.androidsvg.**
-keep class com.caverock.** { *; }
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.v1.** { *; }
-dontwarn com.caverock.androidsvg.R
-dontwarn com.caverock.androidsvg.R$styleable

-keep class com.google.gson.** { *; }

# ---------------------------------------------------------------------------
# Everything below exists because minifyEnabled is on for release builds.
#
# R8 decides what to remove and rename by following references it can see in the
# bytecode. It cannot see through Class.forName or a name built at runtime, so
# anything looked up by name has to be named here instead. Each block says which
# lookup it is protecting; without that, the build succeeds and the app fails.
# ---------------------------------------------------------------------------

# Gson maps JSON keys onto field names. Renaming the fields of a class it parses
# does not fail the build - it silently yields an object with every field null.
# These two are what Gson actually reads: the provider list and the satellite
# imagery list. (MapTileStorage.exportToJson only writes a debug dump, so the
# names it emits do not matter.) The old rule here named
# com.peaknav.network.model, a package that does not exist.
-keep class com.peaknav.network.DownloadProvider { *; }
-keep class com.peaknav.viewer.imgmapprovider.SatelliteProviderRegistry$Entry { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Lucene 3.6.2 (the geonames search index). AttributeSource resolves an Attribute
# interface to its implementation by appending "Impl" to the interface name and
# calling Class.forName, so a renamed class breaks every analyzer chain - which is
# to say all search - at runtime.
-keep class org.apache.lucene.** { *; }
-dontwarn org.apache.lucene.**

# ICU4J, used for Transliterator in MapController. Transliterators are registered
# and instantiated by ID out of ICU's own resource data, never by a reference R8
# can follow.
-keep class com.ibm.icu.** { *; }
-dontwarn com.ibm.icu.**

# Protocol-buffer messages are reflected over by the protobuf runtime; osmpbf's
# generated classes live in crosby.binary.
-keep class com.google.protobuf.** { *; }
-keep class crosby.binary.** { *; }
-dontwarn com.google.protobuf.**

# Mapsforge instantiates render-theme and graphic-factory pieces by name, and its
# Android half reaches for classes the desktop half never links.
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**

# osmdroid (the search screen's map) resolves tile sources and configuration by name.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Compile-time-only annotations and JVM-only APIs referenced by guava and pngj.
# These are warnings about code that is never reached on Android, not real gaps.
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn javax.annotation.**
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.**
-dontwarn org.slf4j.**

# These two lines are used with mapping files; see https://developer.android.com/build/shrink-code#retracing
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
