plugins { alias(libs.plugins.android.test); alias(libs.plugins.kotlin.android); alias(libs.plugins.androidx.baselineprofile) }
android {
 namespace = "io.theficos.spunto.benchmark"
 compileSdk = 35
 targetProjectPath = ":app"
 defaultConfig {
  minSdk = 28
  targetSdk = 35
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  testInstrumentationRunnerArguments["timeout_msec"] = "1200000"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildTypes { maybeCreate("release").apply { isDebuggable = true; signingConfig=signingConfigs.getByName("debug") } }
 experimentalProperties["android.experimental.self-instrumenting"] = true
}
baselineProfile { useConnectedDevices = true }
dependencies {
 implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
 implementation("androidx.test.ext:junit:1.3.0")
 implementation("androidx.test:runner:1.7.0")
 implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
