---
name: harmony-markdown-rendering
description: Implement stable Markdown rendering for HarmonyOS chat UIs using pure ArkUI blocks (headings, lists, quotes, code blocks) and avoid crash-prone WebView dependencies. Use when adding or debugging message Markdown rendering on Harmony.
---

# Harmony Markdown Rendering

## Goal
Render chat Markdown with high stability on HarmonyOS, prioritizing crash resistance over full web-level feature parity.

## Default Strategy
1. Prefer pure ArkUI rendering (`Text`, `Row`, `Column`) over `Web` components.
2. Parse Markdown into block types first, then render each block with fixed styles.
3. Keep AI/transient message paths simple and avoid nested complex component chains.

## Recommended Supported Syntax
- Headings: `#` to `######`
- Bullets: `-`, `*`, `+`
- Ordered list: `1. ...`
- Quote: `> ...`
- Code block: triple backticks
- Divider: `---`, `***`, `___`
- Basic inline cleanup: links/images/emphasis/code marker stripping

## Harmony-Specific Guardrails
- Do not place local variable declarations inside ArkUI builder closures.
- Avoid `for...in`; use `Object.keys()` + indexed loop.
- Avoid `any`/`unknown`; use explicit types.
- Keep long content expandable to reduce layout pressure.
- Treat WebView + remote CDN renderers as fallback only.

## Integration Checklist
- [ ] Markdown renderer is pure ArkUI by default
- [ ] AI message path does not reintroduce past crash points
- [ ] Copy/forward uses original text content, not display-stripped text
- [ ] Build checks pass with `--sync` + `assembleApp`
- [ ] Install/start verified on device/emulator

## Validation Commands
Run from `frontend/harmonyApp`:

```bash
DEVECO_SDK_HOME="/Applications/DevEco-Studio.app/Contents/sdk" "/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw" --sync -p product=default
DEVECO_SDK_HOME="/Applications/DevEco-Studio.app/Contents/sdk" "/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw" assembleApp -p product=default
"/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc" install -r "entry/build/default/outputs/default/app/entry-default.hap"
"/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc" shell aa start -a EntryAbility -b com.silk.harmony
```
