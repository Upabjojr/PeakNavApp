# Publishing PeakNav on F-Droid

F-Droid does not take an APK: it builds every app itself, from a tagged commit of the
public repository, on a server that has Gradle, a JDK and the Android SDK and nothing
else, and signs the result with its own key. Getting PeakNav there is therefore two
things: a repository whose Android target builds from a bare clone with no manual
steps, and a packaging recipe ("metadata") merged into F-Droid's
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository.

## What the repository provides

* **A self-sufficient Android build.** `./gradlew :android:assembleRelease` on a fresh
  clone produces the APK: the interface icons are rendered from their SVG masters by
  `:core:generateIcons`, the launcher mipmaps by `:android:generateLauncherIcons`
  (both run automatically), and the one font the app renders with is in the
  repository. Nothing is downloaded at build time beyond the Maven dependencies.
* **`gradle/wrapper/gradle-wrapper.properties`**, which is how F-Droid's build server
  learns which Gradle to use (it substitutes its own wrapper for the JAR, which is not
  in git).
* **`settings.gradle` includes a module only when its directory exists**, so the recipe
  can delete the non-Android modules instead of patching build files.
* **The store listing** in [`fastlane/metadata/android/`](../fastlane/metadata/android/):
  name, summary, description, icon, screenshots and per-release changelogs, in the
  seven languages of the app. F-Droid reads it from the repository at the commit it
  builds; nothing has to be pasted into fdroiddata.
* **The recipe itself**, drafted in [`com.peaknav.yml`](./com.peaknav.yml).
* **Search without the offline index.** `assets/geonames_index.362/` is built from the
  GeoNames dumps (a large download and a long run, see the root README) and is not
  something F-Droid's server can reasonably reproduce, so the F-Droid build ships
  without it. The app handles that: place search is online only (Nominatim), as on
  iOS, and the description says so.

## Before submitting

1. **Decide on the launcher icon.** The code and every other asset are GPL, but the
   name, the logo and the launcher icon are all rights reserved
   ([TRADEMARK-AND-ASSETS.md](../TRADEMARK-AND-ASSETS.md)). F-Droid accepts that, but
   lists the app with the *NonFreeAssets* anti-feature, which the recipe declares. To
   drop it, release the icon artwork under a free licence (CC BY-SA 4.0, say) while
   keeping the name as a trademark, which is what most projects do and loses nothing:
   trademark, not copyright, is what stops a fork from calling itself PeakNav. Then
   update LICENSE, TRADEMARK-AND-ASSETS.md and remove the `AntiFeatures` block.
2. **Tag a release** that contains these changes. Bump `versionCode` and `versionName`
   in `android/build.gradle` (and `projectVersion` in `gradle.properties`), add the
   matching `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, and tag the
   commit `X.Y.Z` as the earlier releases are tagged. Put that version, code and tag
   into the placeholders of `com.peaknav.yml`. The existing 1.2.0 tag cannot be used:
   the repository at that commit still needed hand-made icons and fonts.
3. **Try the recipe locally** (optional, but it is what the reviewers will do):

   ```bash
   pip install fdroidserver          # or the fdroidserver Debian package
   git clone https://gitlab.com/fdroid/fdroiddata && cd fdroiddata
   cp /path/to/PeakNavApp/fdroid/com.peaknav.yml metadata/
   fdroid readmeta                   # validates the file
   fdroid checkupdates com.peaknav   # finds the tag and version
   fdroid build -v -l com.peaknav    # full build, including the scanner
   ```

   `fdroid build` needs `ANDROID_HOME` set; it downloads the platform and build-tools
   the project asks for. The scanner run inside it is what flags binaries, unknown Maven
   repositories and proprietary dependencies; the two `.bin` model files are the only
   thing it objects to in this repository, and the recipe explains them away with
   `scanignore`. Every Maven repository in the build files is on the allowed list.

## Submitting

Either open a merge request against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/com.peaknav.yml`
(the [contributing guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md)
walks through it; the CI on the merge request runs the same build and scanner), or, to
have somebody else write the recipe, open a "Request For Packaging" at
[gitlab.com/fdroid/rfp](https://gitlab.com/fdroid/rfp/-/issues) linking this
repository. The merge request is faster, and the draft here is most of it.

## After the first release

`AutoUpdateMode: Version` with `UpdateCheckMode: Tags` means F-Droid watches the
repository for new `X.Y.Z` tags, reads `versionCode`/`versionName` from
`android/build.gradle` at the tag, and builds it without anyone touching fdroiddata
again. So each release only needs:

* a `versionCode` bump and a tag, as now;
* a `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters at
  most) for the "what's new" on the listing;
* the Android build kept free of anything F-Droid cannot build or ship: no Google
  Play services, Firebase, or other proprietary libraries, no prebuilt binaries
  checked into the tree, Maven repositories only from the well-known hosts.

F-Droid's key is not the Play key, so the two builds do not update each other; a user
switching store reinstalls, and the downloaded map data (in external storage) is kept.
