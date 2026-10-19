plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
 namespace = "io.theficos.spunto.plugins.dictation"
 compileSdk = 35
 defaultConfig { minSdk = 26 }
 testOptions { unitTests.isIncludeAndroidResources = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }

}
dependencies {
testImplementation(libs.robolectric); testImplementation(libs.androidx.test.core)
implementation("androidx.datastore:datastore-preferences:1.1.1")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
