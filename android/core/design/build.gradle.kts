plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
 namespace = "io.theficos.spunto.core.design"
 compileSdk = 35
 defaultConfig { minSdk = 26 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
}
dependencies {
implementation(libs.androidx.core.ktx)
implementation(libs.compose.foundation)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
implementation(platform(libs.compose.bom))
implementation(libs.compose.ui)
implementation(libs.compose.material3)
implementation("org.commonmark:commonmark:0.24.0")
}
