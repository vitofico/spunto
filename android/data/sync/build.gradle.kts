plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.serialization) }
android { namespace = "io.theficos.spunto.data.sync"; compileSdk = 35; defaultConfig { minSdk = 26 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" }; testOptions { unitTests.isIncludeAndroidResources = true } }
dependencies { api(project(":data:local")); implementation(libs.room.ktx); implementation(libs.okhttp); implementation(libs.kotlinx.serialization.json); implementation(libs.kotlinx.coroutines.android); implementation(libs.androidx.work.runtime); implementation(libs.androidx.datastore.preferences); testImplementation(libs.junit); testImplementation(libs.mockwebserver); testImplementation(libs.robolectric); testImplementation(libs.androidx.test.core); testImplementation(libs.kotlinx.coroutines.test) }
tasks.withType<Test>().configureEach { systemProperty("spunto.fixtures",rootProject.file("../spec/fixtures").absolutePath) }
tasks.withType<Test>().configureEach {
 systemProperty("spunto.integration.url",providers.gradleProperty("spuntoIntegrationUrl").getOrElse(""))
 systemProperty("spunto.integration.password",providers.gradleProperty("spuntoIntegrationPassword").getOrElse("Synthetic-M5-password-only!"))
}
