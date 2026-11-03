# GlucoTrail

Android glucose reader and personal tracking app. Source imported from the existing
GlucoTrail 0.1.60.19 project; 0.1.60.20 adds Settings → App Updates.

The existing application ID `com.aidexx.reader`, databases, preferences, and data
formats are retained. The name shown to users is GlucoTrail.

Build with JDK 17, Android SDK 36, and the included Gradle wrapper:

```sh
sh ./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat`. Set `ANDROID_HOME` or an untracked `local.properties`
with `sdk.dir`. Debug builds and validation need no signing secrets.

App Updates checks public releases in `Comtech8080/GlucoTrail`, supports stable
and beta channels, displays release notes, downloads on request, and validates
SHA-256, package identity, version, and the current signing certificate before
opening Android's installation screen. Automatic daily checks use Android's
persisted JobScheduler and respect the notification preference and Android permissions.

See [release setup and signing continuity](docs/RELEASING.md) and the
[update validation checklist](docs/UPDATES.md).
