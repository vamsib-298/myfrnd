# Offline companion speech

English reminders use Flite 2.2 and its bundled CMU US RMS male voice. Android plays the synthesized waveform from memory. Text in other scripts uses the local Android speech engine when available. License notices ship in the APK under assets/licenses/Flite.txt.

To rebuild the three checked-in Android libraries, unpack the [Flite 2.2 release source](https://deb.debian.org/debian/pool/main/f/flite/flite_2.2.orig.tar.gz) (SHA-256 ab1555fe5adc3f99f1d4a1a0eb1596d329fd6d74f1464a0097c81f53c0cf9e5c). Run build.ps1 with -FliteSource pointing to the extracted directory and -SdkRoot pointing to the Android SDK. The script uses NDK 28.2.13676358 and CMake 3.22.1.
