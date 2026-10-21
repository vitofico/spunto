from pathlib import Path
import json
r=Path(__file__).resolve().parent
tokens=json.loads((r.parents[2]/'web/tokens.json').read_text())
assert list(tokens['catalog']) == list(tokens['themes']), 'Theme catalog and palette IDs must match'
lines=['// Generated from web/tokens.json by android/core/design/generate-tokens.py.','package io.theficos.spunto.core.design','import androidx.compose.ui.unit.dp', 'data class ThemeChoice(val id:String,val name:String,val description:String)', 'val builtInThemes = listOf(']
for theme,details in tokens['catalog'].items():
 lines.append(' ThemeChoice('+','.join(json.dumps(value) for value in (theme,details['name'],details['description']))+'),')
lines.extend([')', 'val builtInTokens: Map<String, Map<String, Map<String, String>>> = mapOf('])
for theme,modes in tokens['themes'].items():
 lines.append(f' "{theme}" to mapOf(')
 for mode,values in modes.items():lines.append(f'  "{mode}" to mapOf('+','.join(f'"{k}" to "{v}"' for k,v in values.items())+'),')
 lines.append(' ),')
lines.append(')')
camel=lambda key:''.join(part if i==0 else part.title() for i,part in enumerate(key.split('-')))
lines.append('object SpuntoSpacing { '+'; '.join(f'val s{px}={px}.dp' for px in tokens['spacing'])+' }')
lines.append('object SpuntoRadius { '+'; '.join(f'val {"r"+key if key.isdigit() else key}={px}.dp' for key,px in tokens['radius'].items())+' }')
lines.append('/** Durations in milliseconds; easing on Android comes from MaterialTheme.motionScheme. */')
lines.append('object SpuntoMotion { '+'; '.join(f'const val {camel(key)}={ms}' for key,ms in tokens['motion'].items() if isinstance(ms,int))+' }')
(r/'src/main/java/io/theficos/spunto/core/design/Tokens.kt').write_text('\n'.join(lines)+'\n')
