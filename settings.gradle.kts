pluginManagement {
    repositories {
        maven { url=uri ("https://maven.aliyun.com/repository/releases") }
        maven { url=uri ("https://maven.aliyun.com/repository/google") }
        maven { url=uri ("https://maven.aliyun.com/repository/central") }
        maven { url=uri ("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url=uri ("https://maven.aliyun.com/repository/public") }
        maven { url=uri ("https://jitpack.io") }
        google()  // Move this to the top and remove the content filter
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Use PREFER_SETTINGS to allow the Flutter plugin to add its own repository without failing the build.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url=uri ("https://maven.aliyun.com/repository/releases") }
        maven { url=uri ("https://maven.aliyun.com/repository/google") }
        maven { url=uri ("https://maven.aliyun.com/repository/central") }
        maven { url=uri ("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url=uri ("https://maven.aliyun.com/repository/public") }
        maven { url=uri ("https://jitpack.io") }
        google()  // Ensure this is present without restrictions
        mavenCentral()
        // It's still good practice to declare the Flutter repo here.
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
    }
}

rootProject.name = "six-iot-sdk-android"

// Include the Flutter module using the correct 'apply' method for settings scripts
apply(from = File(
    settingsDir,
    "../six_iot_flutter/.android/include_flutter.groovy"
))

// Include native Android modules
include(":app")
include(":lib-esp32-blufi-core")
include(":lib-auth")
include(":lib-esp32-blufi-app")
include(":lib-auth-appauth-core")
include(":lib-esp32-npm-app")