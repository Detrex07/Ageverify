# AgeVerify SDK for Android

**AgeVerify** is a secure, privacy-first Age Verification SDK for Android applications. It allows developers to verify a user's age seamlessly through either a standalone AgeVerify app or a built-in "In-App" verification flow.

## 🚀 Key Features

- **Hybrid Resolution Logic**: Automatically checks for a standalone AgeVerify app on the device. If found and a valid token exists, verification is instant.
- **In-App Verification**: Provides a full UI flow (ID scanning + Liveness check) if no standalone app is available.
- **Privacy First**: Uses JWT-based tokens and zero-knowledge principles where possible.
- **Simple API**: Single entry point for all operations.

## 📦 Installation

*(Update this section once you have a Maven repository or library module setup)*

```gradle
dependencies {
    implementation 'com.ageverify:sdk:1.0.0'
}
```

## 🛠 Usage

### 1. Initialize the SDK
Initialize the SDK in your Activity or Application class.

```kotlin
val ageVerify = AgeVerifySDK(context)
```

### 2. Check Age Status
Check if the user is already verified for a specific age requirement.

```kotlin
lifecycleScope.launch {
    when (val result = ageVerify.checkAge(requiredAge = 18)) {
        is AgeCheckResult.Verified -> {
            // User is verified! Access the JWT token
            val token = result.jwt
        }
        is AgeCheckResult.NotVerified -> {
            // User needs to go through the verification flow
            ageVerify.launchVerification(activity)
        }
        is AgeCheckResult.InsufficientAge -> {
            // User is verified but is too young for this content
        }
    }
}
```

### 3. Start Verification Flow
If the user isn't verified, launch the UI flow:

```kotlin
ageVerify.launchVerification(
    activityContext = this,
    jurisdiction = Jurisdiction.GLOBAL_DEFAULT,
    requireFaceMatch = true
)
```

## 📂 Project Structure

- `com.ageverify.sdk`: Main SDK entry point and Bridge logic.
- `com.ageverify.ui`: UI Components for ID Scanning and Liveness detection.
- `com.ageverify.core`: Internal verification engine and crypto logic.

## 🔒 Security
The SDK validates the signature of the standalone AgeVerify app before attempting to communicate with its Content Provider to ensure data integrity.

---
Developed by [apsdhirendra](https://github.com/apsdhirendra)
