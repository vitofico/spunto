package io.theficos.spunto

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import okhttp3.HttpUrl.Companion.toHttpUrl
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.AuthConfig

@Composable fun LoginScreen(model:SpuntoViewModel,reauth:Boolean=false,onDone:()->Unit) {
 val auth by model.auth.collectAsState(); val message by model.message.collectAsState(); val notes by model.notes.collectAsState(); val signingIn by model.reauthenticate.collectAsState()
 var server by remember { mutableStateOf(auth?.serverUrl ?: "") }; var password by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }; var name by remember { mutableStateOf(android.os.Build.MODEL) }
 val lockedServer=reauth && auth!=null && notes.isNotEmpty()
 var consent by remember { mutableStateOf(false) }; var warning by remember { mutableStateOf(false) }; var scan by remember { mutableStateOf(false) }
 var hosted by remember {mutableStateOf(auth?.hosted==true)}
 var browserPath by remember {mutableStateOf("/auth")}
 LaunchedEffect(server,consent) {
  hosted=auth?.hosted==true && auth?.serverUrl?.trimEnd('/')==server.trimEnd('/')
  if(server.startsWith("https://") || server.startsWith("http://") && consent) {
   val options=runCatching {ApiClient(AuthConfig(server.trimEnd('/'),"","",name,consent)).get("/api/v1/auth/options")}.getOrNull()
   val login=browserLoginOptions(options,hosted)
   hosted=login.hosted
   browserPath=login.path
  }
 }
 val context=LocalContext.current
 val keyboard=LocalSoftwareKeyboardController.current
 val focus=LocalFocusManager.current
 LaunchedEffect(scan) {if(scan) {focus.clearFocus();keyboard?.hide()}}
 fun parsed(text:String) {
  when(val input=pairingInput(text,server,code,if(lockedServer) auth?.serverUrl else null)) {
   is PairingInput.Accepted -> {server=input.server;code=input.code;consent=false}
   PairingInput.Invalid -> model.message.value="That pairing QR is invalid. Scan a pairing QR from your server or paste its code."
   PairingInput.DifferentServer -> model.message.value="Sign in again to the same server. Sign out deliberately before connecting a different instance."
  }
  scan=false
 }
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) scan=true else model.message.value="Camera permission was not granted. Paste the pairing code instead." }
 LaunchedEffect(auth,signingIn) { if(auth!=null && !signingIn && (password.isNotEmpty() || code.isNotEmpty())) onDone() }
 Surface(Modifier.fillMaxSize()) {
  if(warning) PlainHttpWarning(server,onConnect={consent=true;warning=false;model.login(server,password,code,name,true)},onBack={warning=false})
  else LoginPage {
   Text(if(reauth) "Sign in again" else "Connect to spunto",style=MaterialTheme.typography.headlineLarge)
   if(reauth) Text("Your device token expired or was revoked. Your notes and unsynced changes are still on this phone.",style=MaterialTheme.typography.bodyMedium) else Text("A quiet place for loose ideas. Sign in to your own server or scan its pairing code.",style=MaterialTheme.typography.bodyMedium)
   OutlinedTextField(server,{server=it;consent=false},label={Text("Server URL")},enabled=!lockedServer,modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Next))
   OutlinedTextField(name,{name=it},label={Text("Phone name")},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next))
   if(!hosted) OutlinedTextField(password,{password=it},label={Text("Owner password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Next))
   if(hosted) {
    Text("Sign in in your server's browser, choose the space, then create a pairing code in Settings.",style=MaterialTheme.typography.bodyMedium)
    Text("Each account and space keeps its own offline notes. Unsent changes stay with their original account.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick={runCatching {val root=server.toHttpUrl();val target=root.resolve(browserPath);require(target!=null && target.host==root.host && target.scheme==root.scheme && target.port==root.port);context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(target.toString())))}.onFailure {model.message.value="Enter a valid server URL first."}}) {Text("Sign in in browser")}
   }
   OutlinedTextField(code,{if(it.startsWith("{")) parsed(it) else code=it},label={Text("Pairing code or QR payload")},modifier=Modifier.fillMaxWidth())
   TextButton(onClick={if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) scan=true else permission.launch(Manifest.permission.CAMERA)}) { Text("Scan pairing QR") }
   if(message.isNotEmpty()) Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
   Button(onClick={if(server.startsWith("http://") && !consent) warning=true else model.login(server,password,code,name,consent)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=server.isNotBlank() && name.isNotBlank() && ((!hosted && password.isNotEmpty()) || code.isNotBlank())) { Text(if(code.isNotBlank()) "Pair this phone" else "Sign in") }
   TextButton(onClick=onDone) { Text("Keep using this phone offline") }
  }
 }
 if(scan) PairingQrSheet(onDismiss={scan=false}) {QrScanner(onValue={if(scan) parsed(it)})}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PairingQrSheet(onDismiss:()->Unit,content:@Composable ()->Unit) {
 val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheetState,scrimColor=MaterialTheme.colorScheme.scrim) {
  Column(Modifier.fillMaxWidth().heightIn(max=640.dp).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    Text("Scan pairing QR",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
    TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)) {Text("Cancel")}
   }
   Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    content()
    Text("You can also cancel and paste the pairing code in the form.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
 }
}
@Composable internal fun LoginPage(content:@Composable ColumnScope.()->Unit) {
 Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),contentAlignment=Alignment.TopCenter) {
  Column(Modifier.widthIn(max=560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
 }
}
@Composable internal fun PlainHttpWarning(server:String,onConnect:()->Unit,onBack:()->Unit) {
 LoginPage {
  Text("Not encrypted",style=MaterialTheme.typography.headlineLarge)
  Text("This server uses plain HTTP. Your password and notes will cross the network unencrypted. Fine on your own network; set up HTTPS before using it elsewhere.",style=MaterialTheme.typography.bodyMedium)
  Text(server,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Button(onClick=onConnect,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Connect on this network")}
  TextButton(onClick=onBack,modifier=Modifier.heightIn(min=48.dp)) {Text("Go back")}
 }
}
