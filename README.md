# Lease Manager Android

Receives a `.leasepack` from the desktop app, shows the PDF, lets the user draw and place a signature
and returns a `.leasesigned` file. Formats: `shared/libs/leasepack/README.md`.

Build (JDK 17-23, e.g. the Android Studio JBR): `JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest assembleDebug`

Limitation: only package integrity is checked, not authenticity (see formats document).
