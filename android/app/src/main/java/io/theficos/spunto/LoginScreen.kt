package io.theficos.spunto

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import okhttp3.HttpUrl.Companion.toHttpUrl
import io.theficos.spunto.data.sync.ApiClient
import io.theficos.spunto.data.sync.AuthConfig

@Composable fun LoginScreen(model:SpuntoViewModel,reauth:Boolean=false,onDone:()->Unit) {
 val auth by model.auth.collectAsState(); val problem by model.signInProblem.collectAsState(); val notes by model.notes.collectAsState(); val signingIn by model.reauthenticate.collectAsState()
 val context=LocalContext.current
 var server by rememberSaveable { mutableStateOf(auth?.serverUrl ?: "https://") }; var password by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }; var name by rememberSaveable { mutableStateOf(auth?.name ?: defaultDeviceName(context)) }
 val lockedServer=reauth && auth!=null && notes.isNotEmpty()
 var consent by remember { mutableStateOf(false) }; var warning by remember { mutableStateOf(false) }; var scan by remember { mutableStateOf(false) }
 var pasteCode by rememberSaveable { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }
 var hosted by remember {mutableStateOf(auth?.hosted==true)}
 var browserPath by remember {mutableStateOf("/auth")}
 val url=if(lockedServer) auth?.serverUrl else normalizedServerUrl(server)
 LaunchedEffect(url,consent) {
  hosted=auth?.hosted==true && auth?.serverUrl?.trimEnd('/')==url
  if(url!=null && (url.startsWith("https://") || consent)) {
   val options=runCatching {ApiClient(AuthConfig(url,"","",name,consent)).get("/api/v1/auth/options")}.getOrNull()
   val login=browserLoginOptions(options,hosted)
   hosted=login.hosted
   browserPath=login.path
  }
 }
 val keyboard=LocalSoftwareKeyboardController.current
 val focus=LocalFocusManager.current
 LaunchedEffect(scan) {if(scan) {focus.clearFocus();keyboard?.hide()}}
 DisposableEffect(model) {onDispose {model.signInProblem.value=null}}
 fun show(field:SignInField,message:String) {model.signInProblem.value=SignInProblem(field,message)}
 fun edited(field:SignInField) {if(problem?.field==field || problem?.field==SignInField.Form) model.signInProblem.value=null}
 fun parsed(text:String) {
  when(val input=pairingInput(text,server,code,if(lockedServer) auth?.serverUrl else null)) {
   is PairingInput.Accepted -> {server=input.server;code=input.code;consent=false;pasteCode=true;edited(SignInField.Code)}
   PairingInput.Invalid -> {pasteCode=true;show(SignInField.Code,"That pairing code isn't valid. Scan or paste the code from the web app's Settings → Devices.")}
   PairingInput.DifferentServer -> show(SignInField.Server,"Sign in again to the same server. Sign out deliberately before connecting a different instance.")
  }
  scan=false
 }
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) scan=true else {pasteCode=true;show(SignInField.Code,"Camera access was not allowed. Paste the pairing code instead.")} }
 fun submit() {
  if(busy) return
  val target=url
  val missing=when {
   target==null -> serverUrlProblem(server)
   name.isBlank() -> SignInProblem(SignInField.Name,"Give this phone a name, so you can find it under Devices.")
   code.isBlank() && hosted -> SignInProblem(SignInField.Code,"Paste the pairing code from the web app.")
   code.isBlank() && password.isEmpty() -> SignInProblem(SignInField.Password,"Enter the owner password.")
   else -> null
  }
  if(missing!=null || target==null) {model.signInProblem.value=missing;return}
  focus.clearFocus()
  if(!lockedServer) server=target
  if(target.startsWith("http://") && !consent) {warning=true;return}
  busy=true;model.login(target,password,code,name.trim(),consent).invokeOnCompletion {busy=false}
 }
 LaunchedEffect(auth,signingIn) { if(auth!=null && !signingIn && (password.isNotEmpty() || code.isNotEmpty())) onDone() }
 Surface(Modifier.fillMaxSize()) {
  if(warning) PlainHttpWarning(url.orEmpty(),onConnect={consent=true;warning=false;busy=true;model.login(url.orEmpty(),password,code,name.trim(),true).invokeOnCompletion {busy=false}},onBack={warning=false})
  else LoginPage(actions={
   problem?.takeIf {it.field==SignInField.Form}?.let {Text(it.message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=12.dp).semantics {liveRegion=LiveRegionMode.Polite})}
   Button(onClick=::submit,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(if(busy) "Signing in…" else if(code.isNotBlank()) "Pair this phone" else "Sign in") }
  }) {
   Text(if(reauth) "Sign in again" else "Connect to spunto",style=MaterialTheme.typography.headlineLarge)
   if(reauth) Text("Your device token expired or was revoked. Your notes and unsynced changes are still on this phone.",style=MaterialTheme.typography.bodyMedium) else Text("A quiet place for loose ideas. Sign in to your own server, or scan its pairing code.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   SignInTextField(server,{server=it;consent=false;edited(SignInField.Server)},"Server address",problem,SignInField.Server,enabled=!lockedServer,keyboard=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrectEnabled=false,imeAction=ImeAction.Next))
   if(!hosted) SignInTextField(password,{password=it;edited(SignInField.Password)},"Owner password",problem,SignInField.Password,secret=true,keyboard=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Go),onGo=::submit)
   SignInTextField(name,{name=it;edited(SignInField.Name)},"Device name",problem,SignInField.Name,hint="Shown under Devices in the web app.",keyboard=KeyboardOptions(imeAction=ImeAction.Go),onGo=::submit)
   if(hosted) {
    Text("Sign in in your server's browser, choose the space, then create a pairing code in Settings.",style=MaterialTheme.typography.bodyMedium)
    Text("Each account and space keeps its own offline notes. Unsent changes stay with their original account.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick={runCatching {val root=url!!.toHttpUrl();val target=root.resolve(browserPath);require(target!=null && target.host==root.host && target.scheme==root.scheme && target.port==root.port);context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(target.toString())))}.onFailure {model.signInProblem.value=serverUrlProblem(server) ?: SignInProblem(SignInField.Server,"Couldn't open this server in your browser.")}}) {Text("Sign in in browser")}
   }
   if(hosted || pasteCode || code.isNotEmpty()) SignInTextField(code,{if(it.trimStart().startsWith("{")) parsed(it) else {code=it;edited(SignInField.Code)}},"Pairing code",problem,SignInField.Code,keyboard=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Go),onGo=::submit)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    TextButton(onClick={if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) scan=true else permission.launch(Manifest.permission.CAMERA)},modifier=Modifier.heightIn(min=48.dp)) { Text("Scan pairing code") }
    if(!(hosted || pasteCode || code.isNotEmpty())) TextButton(onClick={pasteCode=true},modifier=Modifier.heightIn(min=48.dp)) { Text("Paste a code") }
   }
   TextButton(onClick=onDone,modifier=Modifier.heightIn(min=48.dp)) { Text("Keep using this phone offline") }
  }
 }
 if(scan) PairingQrSheet(onDismiss={scan=false}) {QrScanner(onValue={if(scan) parsed(it)})}
}
/** A single-line field whose problem, when it has one, is the sentence under it. */
@Composable private fun SignInTextField(value:String,onValue:(String)->Unit,label:String,problem:SignInProblem?,field:SignInField,enabled:Boolean=true,secret:Boolean=false,hint:String?=null,keyboard:KeyboardOptions,onGo:()->Unit={}) {
 val message=problem?.takeIf {it.field==field}?.message
 OutlinedTextField(value,onValue,Modifier.fillMaxWidth(),enabled=enabled,label={Text(label)},singleLine=true,isError=message!=null,visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,keyboardOptions=keyboard,keyboardActions=KeyboardActions(onGo={onGo()}),
  supportingText=(message ?: hint)?.let {text -> {Text(text,modifier=if(message!=null) Modifier.semantics {liveRegion=LiveRegionMode.Polite} else Modifier)}})
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
/** [actions] stay under the content and above the keyboard, so the main button is always reachable. */
@Composable internal fun LoginPage(actions:(@Composable ColumnScope.()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
 Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
  Column(Modifier.widthIn(max=560.dp).fillMaxWidth()) {
   Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
   actions?.let {Column(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,bottom=16.dp),content=it)}
  }
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
