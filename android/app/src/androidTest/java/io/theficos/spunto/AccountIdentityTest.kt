package io.theficos.spunto

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.spunto.data.sync.AuthConfig
import io.theficos.spunto.data.sync.SignedInAccount
import org.junit.Rule
import org.junit.Test

/** Synthetic UI inputs prove the signed-in name never comes from the phone or the space. */
class AccountIdentityTest {
 @get:Rule val compose=createComposeRule()
 private val alice=AuthConfig("https://notes.example.test","synthetic-a","device","Kitchen phone",userId="alice",workspaceId="shared-space",workspaceRole="viewer")
 @Test fun showsActualNameAndUsernameThenHidesLateIdentityAfterAccountChange() {
  var config by mutableStateOf(alice)
  var identity by mutableStateOf<SignedInAccount?>(SignedInAccount("alice"," Alice ","alice-login"))
  var details by mutableStateOf(false)
  compose.setContent {MaterialTheme {Column {
   AccountIdentityRow(config,identity) {details=true}
   if(details) AccountIdentityDialog(config,identity,onDismiss={details=false},onManage={})
  }}}
  compose.onNodeWithText("Alice").assertIsDisplayed()
  compose.onNodeWithText("Signed in as alice-login").assertIsDisplayed().performClick()
  compose.onNodeWithText("Signed in as Alice").assertIsDisplayed()
  compose.onNodeWithText("Username: alice-login").assertIsDisplayed()
  compose.onNodeWithText("Server: https://notes.example.test").assertIsDisplayed()
  compose.onNodeWithText("This phone is “Kitchen phone”").assertIsDisplayed()
  compose.onNodeWithText("Close").performClick()
  // A late old-account response may be present for one frame; the component checks its authority too.
  compose.runOnIdle {config=alice.copy(userId="bob",token="synthetic-b")}
  compose.onNodeWithText("Alice").assertDoesNotExist()
  compose.onNodeWithText("Signed in as alice-login").assertDoesNotExist()
  compose.onNodeWithText("Account").assertIsDisplayed()
  compose.runOnIdle {identity=SignedInAccount("bob"," "," bob-login ")}
  compose.onNodeWithText("bob-login").assertIsDisplayed()
 }
 @Test fun singleOwnerNeverUsesDeviceNameAsUserName() {
  val local=AuthConfig(alice.serverUrl,"local","device","Kitchen phone")
  compose.setContent {MaterialTheme {AccountIdentityRow(local,null) {}}}
  compose.onNodeWithText("Owner").assertIsDisplayed()
  compose.onNodeWithText("Signed in as Owner").assertIsDisplayed()
  compose.onNodeWithText("Kitchen phone").assertDoesNotExist()
 }
}
