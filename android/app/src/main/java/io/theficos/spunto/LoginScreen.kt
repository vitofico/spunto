package io.theficos.spunto

import android.content.res.Resources

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
import io.theficos.spunto.core.design.SpuntoSpacing

@Composable fun LoginScreen(model:SpuntoViewModel,reauth:Boolean=false,onDone:()->Unit) {
 val resources=LocalContext.current.resources
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
   PairingInput.Invalid -> {pasteCode=true;show(SignInField.Code,resources.getString(R.string.settings_that_pairing_code_isn_t_valid_scan))}
   PairingInput.DifferentServer -> show(SignInField.Server,resources.getString(R.string.settings_sign_in_again_to_the_same_server))
  }
  scan=false
 }
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if(it) scan=true else {pasteCode=true;show(SignInField.Code,resources.getString(R.string.settings_camera_access_was_not_allowed_paste_the))} }
 fun submit() {
  if(busy) return
  val target=url
  val missing=when {
   target==null -> serverUrlProblem(resources,server)
   name.isBlank() -> SignInProblem(SignInField.Name,resources.getString(R.string.settings_give_this_phone_a_name_so_you))
   code.isBlank() && hosted -> SignInProblem(SignInField.Code,resources.getString(R.string.settings_paste_the_pairing_code_from_the_web))
   code.isBlank() && password.isEmpty() -> SignInProblem(SignInField.Password,resources.getString(R.string.settings_enter_the_owner_password))
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
   problem?.takeIf {it.field==SignInField.Form}?.let {Text(it.message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(bottom=SpuntoSpacing.s12).semantics {liveRegion=LiveRegionMode.Polite})}
   Button(onClick=::submit,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(if(busy) resources.getString(R.string.settings_signing_in) else if(code.isNotBlank()) resources.getString(R.string.settings_pair_this_phone) else resources.getString(R.string.settings_sign_in)) }
  }) {
   Text(if(reauth) resources.getString(R.string.settings_sign_in_again) else resources.getString(R.string.settings_connect_to_spunto),style=MaterialTheme.typography.headlineLarge)
   if(reauth) Text(resources.getString(R.string.settings_your_device_token_expired_or_was_revoked),style=MaterialTheme.typography.bodyMedium) else Text(resources.getString(R.string.settings_a_quiet_place_for_loose_ideas_sign),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   SignInTextField(server,{server=it;consent=false;edited(SignInField.Server)},resources.getString(R.string.settings_server_address),problem,SignInField.Server,enabled=!lockedServer,keyboard=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrectEnabled=false,imeAction=ImeAction.Next))
   if(!hosted) SignInTextField(password,{password=it;edited(SignInField.Password)},resources.getString(R.string.settings_owner_password),problem,SignInField.Password,secret=true,keyboard=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Go),onGo=::submit)
   SignInTextField(name,{name=it;edited(SignInField.Name)},resources.getString(R.string.settings_device_name),problem,SignInField.Name,hint=resources.getString(R.string.settings_shown_under_devices_in_the_web_app),keyboard=KeyboardOptions(imeAction=ImeAction.Go),onGo=::submit)
   if(hosted) {
    Text(resources.getString(R.string.settings_sign_in_in_your_server_s_browser),style=MaterialTheme.typography.bodyMedium)
    Text(resources.getString(R.string.settings_each_account_and_space_keeps_its_own),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick={runCatching {val root=url!!.toHttpUrl();val target=root.resolve(browserPath);require(target!=null && target.host==root.host && target.scheme==root.scheme && target.port==root.port);context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(target.toString())))}.onFailure {model.signInProblem.value=serverUrlProblem(resources,server) ?: SignInProblem(SignInField.Server,resources.getString(R.string.settings_couldn_t_open_this_server_in_your))}}) {Text(resources.getString(R.string.settings_sign_in_in_browser))}
   }
   if(hosted || pasteCode || code.isNotEmpty()) SignInTextField(code,{if(it.trimStart().startsWith("{")) parsed(it) else {code=it;edited(SignInField.Code)}},resources.getString(R.string.settings_pairing_code),problem,SignInField.Code,keyboard=KeyboardOptions(autoCorrectEnabled=false,imeAction=ImeAction.Go),onGo=::submit)
   Row(horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s8)) {
    TextButton(onClick={if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) scan=true else permission.launch(Manifest.permission.CAMERA)},modifier=Modifier.heightIn(min=48.dp)) { Text(resources.getString(R.string.settings_scan_pairing_code)) }
    if(!(hosted || pasteCode || code.isNotEmpty())) TextButton(onClick={pasteCode=true},modifier=Modifier.heightIn(min=48.dp)) { Text(resources.getString(R.string.settings_paste_a_code)) }
   }
   TextButton(onClick=onDone,modifier=Modifier.heightIn(min=48.dp)) { Text(resources.getString(R.string.settings_keep_using_this_phone_offline)) }
  }
 }
 if(scan) PairingQrSheet(onDismiss={scan=false}) {QrScanner(onValue={if(scan) parsed(it)})}
}
/** A single-line field whose problem, when it has one, is the sentence under it. */
@Composable private fun SignInTextField(value:String,onValue:(String)->Unit,label:String,problem:SignInProblem?,field:SignInField,enabled:Boolean=true,secret:Boolean=false,hint:String?=null,keyboard:KeyboardOptions,onGo:()->Unit={}) {
 val resources=LocalContext.current.resources
 val message=problem?.takeIf {it.field==field}?.message
 OutlinedTextField(value,onValue,Modifier.fillMaxWidth(),enabled=enabled,label={Text(label)},singleLine=true,isError=message!=null,visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,keyboardOptions=keyboard,keyboardActions=KeyboardActions(onGo={onGo()}),
  supportingText=(message ?: hint)?.let {text -> {Text(text,modifier=if(message!=null) Modifier.semantics {liveRegion=LiveRegionMode.Polite} else Modifier)}})
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PairingQrSheet(onDismiss:()->Unit,content:@Composable ()->Unit) {
 val resources=LocalContext.current.resources
 val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)
 ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheetState,scrimColor=MaterialTheme.colorScheme.scrim) {
  Column(Modifier.fillMaxWidth().heightIn(max=640.dp).padding(horizontal=SpuntoSpacing.s20),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
    Text(resources.getString(R.string.settings_scan_pairing_qr),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
    TextButton(onClick=onDismiss,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_cancel))}
   }
   Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=SpuntoSpacing.s24),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s12)) {
    content()
    Text(resources.getString(R.string.settings_you_can_also_cancel_and_paste_the),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
  }
 }
}
/** [actions] stay under the content and above the keyboard, so the main button is always reachable. */
@Composable internal fun LoginPage(actions:(@Composable ColumnScope.()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
 val resources=LocalContext.current.resources
 Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
  Column(Modifier.widthIn(max=560.dp).fillMaxWidth()) {
   Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(SpuntoSpacing.s24),verticalArrangement=Arrangement.spacedBy(SpuntoSpacing.s16)) {
    Box(Modifier.fillMaxWidth()) {
     io.theficos.spunto.core.design.SpuntoWave(Modifier.align(Alignment.CenterEnd).width(140.dp).height(52.dp),alpha=.4f)
     io.theficos.spunto.core.design.SpuntoWordmark()
    }
    content()
   }
   actions?.let {Column(Modifier.fillMaxWidth().padding(start=SpuntoSpacing.s24,end=SpuntoSpacing.s24,bottom=SpuntoSpacing.s16),content=it)}
  }
 }
}
@Composable internal fun PlainHttpWarning(server:String,onConnect:()->Unit,onBack:()->Unit) {
 val resources=LocalContext.current.resources
 LoginPage {
  Text(resources.getString(R.string.settings_not_encrypted),style=MaterialTheme.typography.headlineLarge)
  Text(resources.getString(R.string.settings_this_server_uses_plain_http_your_password),style=MaterialTheme.typography.bodyMedium)
  Text(server,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Button(onClick=onConnect,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_connect_on_this_network))}
  TextButton(onClick=onBack,modifier=Modifier.heightIn(min=48.dp)) {Text(resources.getString(R.string.settings_go_back))}
 }
}
