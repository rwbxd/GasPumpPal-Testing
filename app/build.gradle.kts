import com.google.protobuf.gradle.*

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.rwbdev.prototest"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.rwbdev.prototest"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

val camerax_version = "1.2.2"
dependencies {

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")


    implementation("com.google.protobuf:protobuf-java:3.6.1")
    implementation("com.google.protobuf:protobuf-kotlin:3.19.4")
    implementation("androidx.datastore:datastore:1.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")

    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-video:${camerax_version}")

    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation("androidx.camera:camera-extensions:${camerax_version}")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.1")


}

protobuf {
    protoc {
        // The artifact spec for the Protobuf Compiler
        artifact = "com.google.protobuf:protoc:3.19.4"
    }
    generateProtoTasks {
//        ofSourceSet("main").forEach {
//            it.plugins {
//                // Apply the "grpc" plugin whose spec is defined above, without
//                // options. Note the braces cannot be omitted, otherwise the
//                // plugin will not be added. This is because of the implicit way
//                // NamedDomainObjectContainer binds the methods.
//                id("grpc") { }
//            }
//        }
        all().forEach { task ->
            task.plugins{
                create("kotlin")
                create("java")
            }
        }
    }
}