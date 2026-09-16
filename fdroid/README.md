# Publishing PeakNav on F-Droid

F-Droid does not take an APK: it builds every app itself, from a tagged commit of the public
repository, on a server with Gradle, a JDK and the Android SDK and nothing else, and signs the
result with its own key. So PeakNav needs two things there: an Android target that builds from a
bare clone with no manual steps, and a packaging recipe merged into
[fdroiddata](https://gitlab.com/fdroid/fdroiddata).

## What the repository provides

* **A bare-clone Android build.** `./gradlew :android:assembleRelease` on a fresh clone, with
  the other targets deleted as the recipe does, produces the APK:
  * the interface icons are rendered from their SVG masters by `:core:generateIcons` (Batik,
    pure Java) at the sizes in `assets_nonshared/icons/icons.txt` - the same list
    `build_icons.sh` reads, so the two cannot drift;
  * the launcher mipmaps by `:android:generateLauncherIcons`, from the logo masters;
  * the one font the app renders with, Liberation Sans Regular (SIL OFL 1.1), is committed with
    its licence;
  * all dependencies come from Maven Central and Google's Maven repository.
* **`gradle/wrapper/gradle-wrapper.properties`**, which is how F-Droid picks the Gradle version
  (the wrapper JAR stays out of git; F-Droid supplies its own).
* **`settings.gradle` includes a module only when its directory exists**, so the recipe deletes
  the non-Android targets instead of patching build files.
* **No offline place index.** `assets/geonames_index.362/` is built separately from the GeoNames
  dumps and is not something F-Droid's server can rebuild, so this build ships without it; the
  app then searches online only (Nominatim), as the listing says.
* **The store listing** in [`fastlane/metadata/android/`](../fastlane/metadata/android/): title,
  summary, description in the app's seven languages, the icon and eight portrait store
  screenshots; the credit for the photo in the picture-overlay one is in the descriptions and in
  [`SCREENSHOT_PHOTO_CREDITS.md`](./SCREENSHOT_PHOTO_CREDITS.md).
* **The recipe**, drafted in [`com.peaknav.fdroid.yml`](./com.peaknav.fdroid.yml).

Verified on this branch: `:android:assembleRelease` from a bare clone with the other modules
removed builds the APK (icons, launcher icon and font inside), `fdroid scanner` finds no
non-free classes in it, and the source scan reports only the two model `.bin` files the recipe
lists under `scanignore`.

## Decisions before submitting

1. **The launcher icon licence.** The name, logo and launcher icon are all rights reserved, so
   F-Droid lists the app with the *NonFreeAssets* anti-feature, which the recipe declares.
   Releasing the icon artwork under a free licence while keeping *PeakNav* as a trademark would
   remove the flag; trademark, not copyright, is what stops a fork calling itself PeakNav.
2. **Signing.** F-Droid signs with its own key. The F-Droid build therefore has its own package
   id, `com.peaknav.fdroid` (passed as a Gradle property by the recipe), so it installs beside the
   Play/GitHub `com.peaknav` rather than clashing with it; the two keep separate settings and
   downloaded maps. The alternative is a *reproducible build*: F-Droid builds,
   checks the result is bit-identical to an APK you sign and attach to the GitHub release, and
   ships yours. That lets users move between GitHub and F-Droid freely, but needs the release
   build to be byte-for-byte reproducible and the recipe to carry `Binaries:` and
   `AllowedAPKSigningKeys:`; it is not set up here.

## Releasing

1. Merge this branch, bump `versionCode`/`versionName` in `android/build.gradle` (and
   `projectVersion` in `gradle.properties`), add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters at most), and
   tag the commit `X.Y.Z` as before. Tags before this branch cannot be built: they still needed
   hand-made icons and the font.
2. Put that version, code and tag into the placeholders of `com.peaknav.fdroid.yml`.
3. Try it (optional; it is what the reviewers run):

   ```bash
   pip install fdroidserver
   git clone https://gitlab.com/fdroid/fdroiddata && cd fdroiddata
   cp /path/to/PeakNavApp/fdroid/com.peaknav.fdroid.yml metadata/
   fdroid readmeta && fdroid lint com.peaknav.fdroid
   fdroid build -v -l com.peaknav.fdroid
   ```

4. Open a merge request on fdroiddata adding `metadata/com.peaknav.fdroid.yml`
   ([contributing guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md)).

After the first release, `AutoUpdateMode: Version` with `UpdateCheckMode: Tags` makes F-Droid pick
up each new `X.Y.Z` tag by itself. Each release then needs only the version bump, the tag and a
changelog file - and an Android build kept free of anything F-Droid cannot build: no Play
services or other proprietary libraries, no prebuilt binaries, Maven repositories only from
the allowed hosts.
