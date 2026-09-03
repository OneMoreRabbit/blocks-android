plugins {
    id("com.android.application")
}

android {
    namespace = "dev.blocks.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.blocks.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "BLOCKS_API_BASE_URL",
            "\"https://api.blocks.onemorerabbit.co.uk\"",
        )
        buildConfigField(
            "String",
            "BLOCKS_GOOGLE_SERVER_CLIENT_ID",
            "\"12259488358-ogmpp0cg1a8v1pbvkoivo9lgr94n0kb9.apps.googleusercontent.com\"",
        )
        buildConfigField("String", "BLOCKS_SERVICE_API_VERSION", "\"0.1\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "BLOCKS_API_BASE_URL", "\"http://blocks-api:8080\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
