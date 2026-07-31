# Introduction
DroidFS is an Android application with an internal file manager on top of "volumes". This version uses Plain volumes: files are stored on disk without encryption, but access through the app is protected by a password.

# Setup

Install the required packages:

For Debian-based Linux distributions:
```
$ sudo apt-get install openjdk-17-jdk-headless build-essential pkg-config git gnupg2 wget npm
```

For Arch Linux and derivatives:
```
$ sudo pacman -S jdk17-openjdk gcc make patch pkgconf git gnupg wget npm
```

Package names might be similar for other distributions. Don't hesitate to ask if you're having trouble with this.

Then, you have to install the Android SDK using the `sdkmanager` [command line tool](https://developer.android.com/studio#command-line-tools-only). **You DON'T need to install Android Studio to build an Android app!** Android Studio is an infamous bloatware bundled with trackers that will be more useful for consuming your entire RAM and heating your house than for building any piece of software.

You can follow [this guide](https://developer.android.com/tools/sdkmanager) to setup `sdkmanager`, but basically it should be:
```
$ export ANDROID_HOME="<PATH>"         <-- choose any path you like as the location of the Android SDK installation
$ mkdir -p "$ANDROID_HOME/cmdline-tools"
$ unzip -d "$ANDROID_HOME/cmdline-tools" commandline-tools-*.zip
$ cd "$ANDROID_HOME/cmdline-tools"
$ mv cmdline-tools latest
```

Install the Android Native Development Kit (NDK) version `28.2.13676358` (r28c) for FFmpeg support:
```
$ "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'ndk;28.2.13676358'
$ export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
```

The source code should be authenticated before being built. To verify the signatures, you will need my PGP key:
```
$ gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys AFE384344A45E13A
```
Fingerprint: `B64E FE86 CEE1 D054 F082  1711 AFE3 8434 4A45 E13A` \
Email: `Hardcore Sushi <hardcore.sushi@disroot.org>`

# Download sources
Download DroidFS source code:
```
$ git clone --depth=1 https://forge.chapril.org/hardcoresushi/DroidFS.git
```
Verify sources:
```
$ cd DroidFS
$ git verify-commit HEAD
```
__Don't continue if the verification fails!__

# Build
If you know your CPU ABI, you can specify it to build scripts in order to speed up compilation time. If you don't know it, or want to build for all ABIs, just leave the field empty.

Start by compiling FFmpeg:
```
$ cd app/ffmpeg
$ ./build.sh [<ABI>]
```

## Compile APKs
```
$ ./gradlew assembleRelease
```

# Sign APKs
If the build succeeds, you will find the unsigned APKs in `app/build/outputs/apk/release/`. These APKs need to be signed in order to be installed on an Android device.

If you don't already have a keystore, you can create a new one by running:
```
$ keytool -genkey -keystore <output file> -alias <key alias> -keyalg EC -validity 10000
```
Then, sign the APK with:
```
$ "$ANDROID_HOME/build-tools/37.0.0/apksigner" sign --out DroidFS-signed.apk -v --ks <keystore> app/build/outputs/apk/release/<unsigned apk file>
```
Now you can install `DroidFS-signed.apk` on your device.
