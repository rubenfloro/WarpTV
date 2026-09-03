# WARP TV

Android TV application that embeds WireGuard and generates a Cloudflare WARP WireGuard profile locally.

## V1 behavior

- No automatic VPN connection at application launch.
- First button press generates a WARP registration and stores the resulting WireGuard configuration encrypted with Android Keystore.
- After configuration, the main button toggles the VPN ON/OFF.
- The WireGuard tunnel is embedded using `com.wireguard.android:tunnel`.
- Universal APK build: ABI splits are disabled and the workflow uploads the single debug APK.
- Target Android TV / Android 8.0+ (minSdk 24).

## WARP registration flow

The original generator's documented flow is:

1. Generate a WireGuard keypair locally.
2. Register the public key with Cloudflare WARP.
3. Build the complete WireGuard configuration.

This app performs that flow natively. It does not depend on the generator web page or its CORS proxy.

## Build

Open the project in Android Studio and run `assembleDebug`.

The repository also includes `.github/workflows/build-apk.yml`, which builds a universal debug APK in GitHub Actions using JDK 17, Gradle 8.13 and Android SDK 36. The uploaded file is named `warp-tv-universal-debug.apk`.

## Important

The Cloudflare WARP registration endpoint used by the original project is unofficial/undocumented. Availability or response format may change. The app therefore validates the required response fields and reports a clear error if registration fails.

The generated profile contains a WireGuard private key. It is stored encrypted using Android Keystore and is never intentionally displayed by the app.

## Licenses

WireGuard Android's embeddable tunnel library is Apache-2.0. The original WARP generator is a separate project with its own repository/license terms. Before redistribution, keep the applicable attribution/license notices and review the upstream repository's current license.
