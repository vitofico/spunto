import java.time.LocalDate
import java.util.zip.ZipFile

plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); alias(libs.plugins.androidx.baselineprofile) }
val spuntoVersionCode = providers.gradleProperty("spuntoVersionCode").orNull?.let { raw ->
 raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 } ?: throw GradleException("spuntoVersionCode must be an integer from 1 to 2100000000.")
} ?: throw GradleException("Set spuntoVersionCode in android/gradle.properties.")
val spuntoVersionName = providers.gradleProperty("spuntoVersionName").orNull?.also {
 require(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}\\.[1-9][0-9]*").matches(it)) { "spuntoVersionName must use CalVer YYYY.MM.DD.<run>, with run starting at 1." }
 val date=it.split('.').take(3).joinToString("-")
 require(runCatching { LocalDate.parse(date) }.isSuccess && !date.startsWith("0000")) { "spuntoVersionName must contain a valid calendar date." }
} ?: throw GradleException("Set spuntoVersionName in android/gradle.properties.")
android {
 namespace = "io.theficos.spunto"
 compileSdk = 35
 defaultConfig { applicationId = "io.theficos.spunto"; minSdk = 26; targetSdk = 35; versionCode = spuntoVersionCode; versionName = spuntoVersionName; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; testInstrumentationRunnerArguments["timeout_msec"] = "600000" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
 // Compile original profiles with the R8 mapping; synthetic profile rewriting can race.
 experimentalProperties["android.experimental.art-profile-r8-rewriting"] = false
 buildTypes {
  debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"; resValue("string", "app_name", "Spunto Debug"); resValue("string", "application_id", "io.theficos.spunto.debug"); isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-debug.pro"); testProguardFiles("proguard-android-test.pro") }
  release { isMinifyEnabled = true; vcsInfo.include = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
 }
}
baselineProfile { mergeIntoMain = true; saveInSrc = true; automaticGenerationDuringBuild = false }
// Public release receipts record source identity; checkout-specific APK metadata breaks rebuilds.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { variant ->
 variant.dependenciesInfo.includeInApk = false
 variant.dependenciesInfo.includeInBundle = false
}
androidComponents.finalizeDsl { dsl ->
 dsl.buildTypes.findByName("benchmarkRelease")?.apply { isDebuggable=false;isMinifyEnabled=true;isShrinkResources=true;signingConfig=dsl.signingConfigs.getByName("debug") }
 dsl.buildTypes.findByName("nonMinifiedRelease")?.apply { signingConfig=dsl.signingConfigs.getByName("debug") }
}
dependencies {
 baselineProfile(project(":benchmark")); implementation("androidx.profileinstaller:profileinstaller:1.4.1"); implementation("androidx.work:work-runtime-ktx:2.9.1")
 implementation(libs.room.ktx); implementation(libs.kotlinx.coroutines.android); implementation(libs.kotlinx.serialization.json); implementation(libs.compose.foundation)
 implementation(project(":core:model")); implementation(project(":core:design")); implementation(project(":core:plugins")); implementation(project(":data:local")); implementation(project(":data:sync")); implementation(project(":plugins:dictation")); implementation(project(":plugins:quick-capture"))
 implementation(libs.androidx.core.ktx); implementation(libs.androidx.activity.compose); implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.material3)
 implementation("androidx.biometric:biometric:1.1.0")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6"); implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6"); implementation("androidx.paging:paging-compose:3.3.2")
 implementation("com.google.zxing:core:3.5.3"); implementation("io.coil-kt:coil-compose:2.7.0")
 androidTestImplementation("androidx.test.ext:junit:1.3.0"); androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0"); androidTestImplementation("androidx.test:core:1.7.0"); androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation("androidx.compose.ui:ui-test-junit4"); androidTestImplementation("androidx.test:runner:1.7.0")
 debugImplementation("androidx.compose.ui:ui-test-manifest"); debugImplementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
 testImplementation("junit:junit:4.13.2")
}

val verifyApkBudget by tasks.registering {
 group="verification"
 doLast {
  val apks=fileTree(layout.buildDirectory.dir("outputs/apk")) { include("**/*.apk");exclude("androidTest/**") }.files
  check(apks.isNotEmpty()) { "Build an app APK before checking its budget." }
  // Incremental packaging leaves dead space in the file, so count the packaged entries; CI also checks the fresh file.
  apks.sortedBy { it.path }.forEach { apk -> val size=ZipFile(apk).use { zip -> zip.entries().asSequence().sumOf { entry -> entry.compressedSize } };check(size<=15_000_000L) { "${apk.name}: $size bytes of entries exceed the 15 MB APK budget." };logger.lifecycle("${apk.name}: $size / 15000000 bytes of entries") }
 }
}
tasks.configureEach { if(name.matches(Regex("assemble(?!.*AndroidTest)[A-Z].*"))) finalizedBy(verifyApkBudget) }
tasks.register("checkApkSize") { group="verification";dependsOn("assembleDebug",verifyApkBudget) }
