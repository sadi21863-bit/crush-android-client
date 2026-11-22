# V2-7 / V2-8 — external state of the art (2026-11-22)

Research-only. No code changed. All sources were retrieved **2026-11-22** unless a row says otherwise. Primary sources are preferred; vendor benchmarks and marketing claims are flagged. V2-7 and V2-8 have no brief on disk (`docs/v2/V2-7-8-codebase-inventory-2026-11-22.md`), so the verdicts and the V2-7/V2-8 split below are **proposals, not owner intent**.

**Bottom line:** no external framework is adoptable as-is for V2-7. AndroidX A2UI Compose is the only credible native Compose path, but it is alpha, still on protocol 0.9.1, and requires `compileSdk 37.1`. Schema-constrained model output is not available through Crush today. The safe foundation is a host-owned, closed, deterministic block registry; A2UI is an experiment, not a dependency.

## Source table

| Source | What it is | Retrieved |
|---|---|---|
| https://developer.android.com/jetpack/androidx/releases/a2ui-compose | AndroidX A2UI Compose release notes (alpha02, 2026-10-21) | 2026-11-22 |
| https://dl.google.com/dl/android/maven2/androidx/a2ui/compose/compose-ui/maven-metadata.xml | Google Maven metadata; latest = `1.0.0-alpha02`, lastUpdated 2026-10-21 | 2026-11-22 |
| https://dl.google.com/dl/android/maven2/androidx/a2ui/compose/compose-ui/1.0.0-alpha02/compose-ui-1.0.0-alpha02.aar | A2UI Compose AAR; `AndroidManifest.xml` minSdk 24; `aar-metadata.properties` minCompileSdk 37.1 | 2026-11-22 |
| https://raw.githubusercontent.com/a2ui-project/a2ui/main/specification/versions.json | A2UI protocol version status (v1.0 current; 0.9.1 previous) | 2026-11-22 |
| https://a2ui.org/concepts/catalogs/ | A2UI catalogs: JSON Schema components/functions, native rendering, validation | 2026-11-22 |
| https://a2ui.org/specification/v1.0-a2ui/ | A2UI v1.0 protocol spec (actions, functions, `allowedCallers`, metadata) | 2026-11-22 |
| https://a2ui.org/guides/client-integration/ | A2UI client integration; says alpha02 targets v0.9.1, v1.0 in progress | 2026-11-22 |
| https://developer.android.com/develop/ui/compose/agentic | Official Android A2UI guide (Compose renderer, catalogs, 0.9.1 support) | 2026-11-22 |
| https://developer.android.com/develop/ui/compose/agentic/manage-catalogs | Official guide: Basic/custom/hybrid catalogs, versioning, negotiation | 2026-11-22 |
| https://api.github.com/repos/a2ui-project/a2ui | Upstream repo metadata: 17,911★, 495 open issues, pushed 2026-11-22 | 2026-11-22 |
| https://developers.googleblog.com/a2ui-10-build-delightful-fast-reliable-agentic-experiences/ | A2UI 1.0 announcement, 2026-11-11 | 2026-11-22 |
| https://github.com/vercel-labs/json-render | json-render repo/README (Apache-2.0, 19.8k★, renderers) | 2026-11-22 |
| https://api.github.com/repos/vercel-labs/json-render/releases?per_page=5 | json-render releases; latest v0.23.2, 2026-11-19 | 2026-11-22 |
| https://api.github.com/repos/CopilotKit/CopilotKit | CopilotKit metadata (MIT, 38.9k★, active 2026-11-22) | 2026-11-22 |
| https://api.github.com/repos/CopilotKit/OpenIntelligentUI | OpenIntelligentUI metadata (MIT, 4.2k★, active 2026-11-20) | 2026-11-22 |
| https://docs.copilotkit.ai/agent-spec/generative-ui/components | CopilotKit components/surfaces docs; still calls A2UI 1.0 a candidate | 2026-11-22 |
| https://docs.ag-ui.com/ | AG-UI protocol overview, supported clients/SDKs | 2026-11-22 |
| https://docs.ag-ui.com/concepts/generative-ui-specs | AG-UI vs A2UI/MCP-UI/Open-JSON-UI distinction | 2026-11-22 |
| https://github.com/modelcontextprotocol/ext-apps | MCP Apps official spec/SDK; stable 2026-01-26; iframe/HTML | 2026-11-22 |
| https://blog.modelcontextprotocol.io/posts/2025-11-21-mcp-apps/ | MCP Apps (SEP-1865) design/security rationale | 2026-11-22 |
| https://api.github.com/repos/MCP-UI-Org/mcp-ui | MCP-UI community repo (Apache-2.0, 5.3k★) | 2026-11-22 |
| https://developers.openai.com/plugins/build/chatgpt-ui | OpenAI: ChatGPT UI is MCP Apps; iframe, `_meta.ui.resourceUri`, CSP | 2026-11-22 |
| https://developers.openai.com/plugins/guides/security-privacy | OpenAI plugin security: isolated iframe, CSP, prompt-injection assumptions | 2026-11-22 |
| https://developers.openai.com/api/docs/guides/structured-outputs | OpenAI Structured Outputs (JSON Schema, strict) | 2026-11-22 |
| https://openrouter.ai/docs/features/structured-outputs | OpenRouter structured outputs (passthrough, per-endpoint) | 2026-11-22 |
| https://ai.google.dev/gemini-api/docs/structured-output | Gemini structured outputs (response_format/schema) | 2026-11-22 |
| https://console.groq.com/docs/structured-outputs | Groq structured outputs (strict vs best-effort) | 2026-11-22 |
| https://platform.claude.com/docs/en/build-with-claude/structured-outputs | Anthropic structured outputs (output_config.format) | 2026-11-22 |
| https://firebase.google.com/docs/ai-logic/generate-structured-output | Firebase AI Logic `responseSchema` (Kotlin/Android) | 2026-11-22 |
| `spec/crush-openapi.json` (local) + https://github.com/charmbracelet/crush/blob/main/schema.json | Crush local API and model config schema | 2026-11-22 |
| https://github.com/charmbracelet/crush/issues/1034 | Open request for structured/JSON output from `crush run` | 2026-11-22 |
| https://ai-sdk.dev/docs/reference/ai-sdk-rsc/stream-ui | Vercel AI SDK `streamUI` (RSC, experimental) | 2026-11-22 |
| https://docs.thesys.dev/legacy/guides/what-is-thesys-c1 | Thesys C1 (now archived; OpenUI recommended) | 2026-11-22 |
| https://support.claude.com/en/articles/9487310-what-are-artifacts-and-how-do-i-use-them | Claude artifacts (model-authored HTML/React in Claude apps) | 2026-11-22 |
| https://www.openui.com/docs/openui-lang/reliability | OpenUI reliability benchmark (A2UI/json-render/OpenUI; vendor-run) | 2026-11-22 |
| https://verygood.ventures/blog/generative-ui-hallucinations-four-layer-defense/ | GenUI failure taxonomy and four-layer defense | 2026-11-22 |
| https://www.infoworld.com/article/4210616/a-better-approach-to-generative-ui.html | "Treat model output as untrusted input" analysis | 2026-11-22 |
| https://arxiv.org/html/2604.16354 | Evidence-classified mapping of A2UI/GenUI; evidence gaps | 2026-11-22 |
| https://aclanthology.org/2026.emnlp-main.1158/ | EvoGenUI-Bench (multi-turn generative UI success rates) | 2026-11-22 |
| https://genai.owasp.org/llmrisk/llm01-prompt-injection/ | OWASP LLM01:2025 prompt injection | 2026-11-22 |
| https://developer.android.com/privacy-and-security/security-tips | Android intents/exported/input-validation guidance | 2026-11-22 |
| https://modelcontextprotocol.io/specification/2025-06-18/basic/security_best_practices | MCP security best practices (SSRF, URL schemes, local servers) | 2026-11-22 |
| https://github.com/lmee/A2UI-Android | Android/Compose A2UI fork/demo | 2026-11-22 |
| https://github.com/BinaryTape/a2ui-kmp | Kotlin Multiplatform wrapper of AndroidX A2UI | 2026-11-22 |
| https://github.com/uny/a2ui-compose | Compose Multiplatform A2UI renderer | 2026-11-22 |

## 1. AndroidX A2UI

- **Coordinates/version.** `androidx.a2ui.compose:compose-runtime`, `:compose-ui`, `:compose-ui-testing`; `androidx.a2ui:a2ui-model`, `:a2ui-engine`; and `androidx.compose.material3:material3-a2ui` for the Basic Catalog. Latest is **`1.0.0-alpha02`**, published **2026-10-21**. The `compose-ui` metadata lists only alpha01 and alpha02; there is **no stable, beta, or RC**. `1.0.0-alpha02` is still current on 2026-11-22. ([release notes](https://developer.android.com/jetpack/androidx/releases/a2ui-compose); [Maven metadata](https://dl.google.com/dl/android/maven2/androidx/a2ui/compose/compose-ui/maven-metadata.xml))
- **What it renders.** A2UI protocol messages (`createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`) into **native Compose** widgets. The Material 3 Basic Catalog covers layout (`Row`, `Column`, `List`, `Card`, `Tabs`, `Modal`, `Divider`), display/media (`Text`, `Image`, `Icon`, `Video`, `AudioPlayer`), and inputs (`Button`, `TextField`, `CheckBox`, `ChoicePicker`, `Slider`, `DateTimeInput`). Custom catalogs can replace or subset it. It is Compose-native and does **not** execute arbitrary code. ([release notes](https://developer.android.com/jetpack/androidx/releases/a2ui-compose); [Android guide](https://developer.android.com/develop/ui/compose/agentic))
- **minSdk/compileSdk.** The `compose-ui` AAR declares **minSdk 24**, so the app's minSdk 29 is compatible. It also declares **`minCompileSdk=37`, `minCompileMinorSdk=1`** and AGP ≥ 9.1.0; the app's `compileSdk = 37` would have to become **37.1** before this dependency can be added. ([AAR](https://dl.google.com/dl/android/maven2/androidx/a2ui/compose/compose-ui/1.0.0-alpha02/compose-ui-1.0.0-alpha02.aar); `app/build.gradle.kts:44`; [AndroidX default minSdk 24](https://developer.android.com/jetpack/androidx/versions))
- **API stability.** Alpha. AndroidX promises binary compatibility only after the public API reaches **stable 1.0.0**; before that, APIs can change. The library uses `A2uiComponent` → future `A2uiComponentV2` deprecation mechanics for protocol evolution. ([Android guide](https://developer.android.com/develop/ui/compose/agentic))
- **Protocol version — the central mismatch.** A2UI **v1.0 is current** (announced 2026-11-11); v0.9.1 is previous. The AndroidX renderer **currently implements v0.9.1**, and A2UI's own client guide says `1.0.0-alpha02` targets v0.9.1 while v1.0 support is "in progress". The library version `1.0.0-alpha02` is **not** protocol 1.0; a third-party KMP wrapper explicitly warns about this. ([versions.json](https://raw.githubusercontent.com/a2ui-project/a2ui/main/specification/versions.json); [Android guide](https://developer.android.com/develop/ui/compose/agentic); [client integration](https://a2ui.org/guides/client-integration/); [A2UI 1.0 post](https://developers.googleblog.com/a2ui-10-build-delightful-fast-reliable-agentic-experiences/); [a2ui-kmp README](https://github.com/BinaryTape/a2ui-kmp))
- **Declarative catalogue / deterministic mapping.** Yes. A `catalog.json` declares each component and function with JSON Schema; the renderer pairs each schema with a native composable. The agent may only use declared components/properties; the renderer validates and rejects unknown/malformed payloads. Catalogs are versioned (`catalogId`), can be negotiated, and multiple versions can be registered at once; in 0.9.1 a surface binds to one catalog, while v1.0 adds multi-catalog surfaces. Given a valid message tree, rendering is deterministic; **generation is still probabilistic**. ([A2UI catalogs](https://a2ui.org/concepts/catalogs/); [Android catalog guide](https://developer.android.com/develop/ui/compose/agentic/manage-catalogs))
- **Official guide.** Yes: the Android "Agent-to-UI renderer for Jetpack Compose" page, plus catalog and custom-component subpages. ([guide](https://developer.android.com/develop/ui/compose/agentic))
- **License/repo/release notes/issues.** Apache-2.0. Source lives in `androidx/androidx` under `a2ui/`; release notes are on developer.android.com; feedback is the AndroidX issue tracker component **2097774** (counts need sign-in). Upstream `a2ui-project/a2ui` is active: 17,911★, 495 open issues, pushed 2026-11-22. ([LICENSE](https://github.com/a2ui-project/a2ui/blob/main/LICENSE); [repo](https://api.github.com/repos/a2ui-project/a2ui); [release notes](https://developer.android.com/jetpack/androidx/releases/a2ui-compose))
- **Verdict: reference-only today; experiment-only in V2-8.** The design is the closest match to this app (native Compose, no WebView, closed catalogue), but alpha + protocol 0.9.1 + compileSdk 37.1 make it unsuitable as a V2-7 foundation under the project's stable-dependency posture. Do not adopt as a production dependency without an explicit decision to accept alpha and move compileSdk.

## 2. Vercel json-render

- **Repo/license/activity.** `vercel-labs/json-render`, **Apache-2.0**, **19.8k★**, 68 open issues and 68 open PRs, 266 commits, latest **v0.23.2 on 2026-11-19**, pushed 2026-11-22. Active. ([repo](https://github.com/vercel-labs/json-render); [releases](https://api.github.com/repos/vercel-labs/json-render/releases?per_page=5))
- **What it is.** A **TypeScript generative-UI framework** with its own JSON spec, not an independent open standard: `@json-render/core` provides catalogs, schemas, state, actions, dynamic props, and SpecStream; first-party renderers exist for React, Vue, Svelte, Solid, React Native, shadcn, PDF/email/chat/3D, and SwiftUI (v0.23.0). Its README calls it "the Generative UI framework" and claims JSON output matches the schema; that is a product claim, not an independent guarantee. ([repo](https://github.com/vercel-labs/json-render))
- **Compose/Android path.** **None found.** The React Native renderer is JavaScript, not native Kotlin/Compose. A GitHub search for `json-render compose` returned 3 repos, none a Compose renderer. ([search](https://api.github.com/search/repositories?q=json-render+compose))
- **Verdict: reference-only.** Useful ideas: flat `{root, elements}` spec, catalog + Zod schemas, streamed spec compiler, host-owned registry. No dependency for this app.

## 3. CopilotKit / OpenIntelligentUI / AG-UI

- **CopilotKit.** MIT, 38.9k★, 480 open issues, pushed 2026-11-22. A frontend stack for agents: React/Angular/**React Native** UI, runtime, and integrations. Its "Mobile" support is React Native; there is no native Compose renderer. ([repo](https://api.github.com/repos/CopilotKit/CopilotKit))
- **OpenIntelligentUI.** MIT, 4.2k★, active 2026-11-20. Architecture: Next.js app + Python LangGraph/Deep Agent + CopilotKit runtime; custom UI is streamed as **HTML/CSS/JS into an isolated iframe** (`generateSandboxedUi`/Websandbox); basic tables use A2UI, charts/diagrams use Open Generative UI. It is a web architecture, not an Android library. ([repo](https://api.github.com/repos/CopilotKit/OpenIntelligentUI); [architecture](https://github.com/CopilotKit/OpenIntelligentUI/blob/main/docs/architecture.md))
- **AG-UI.** MIT, 21.1k★, active 2026-11-21. An **event-based agent↔user protocol** over HTTP/SSE/WebSocket; it standardizes message/state/tool/interrupt events. It is explicitly **not** a generative-UI spec; A2UI, MCP Apps, and Open-JSON-UI are payload specs that can ride on it. A community **Kotlin** SDK is listed, so a native Android client is technically possible without a WebView, but AG-UI supplies **no renderer**, and Crush does not speak AG-UI. ([overview](https://docs.ag-ui.com/); [generative-UI specs](https://docs.ag-ui.com/concepts/generative-ui-specs); [SDK table](https://docs.ag-ui.com/))
- **Verdict: CopilotKit/OpenIntelligentUI reject; AG-UI reference-only.** AG-UI would be a new transport adapter around Crush, not a UI foundation, and it would not remove the need for a host-owned renderer.

## 4. MCP UI / MCP Apps / OpenAI Apps SDK / Anthropic MCP

- **There is no single standard.** The landscape splits into transport (MCP, AG-UI), UI schema (A2UI, Open-JSON-UI), and renderer (HTML iframe vs native Compose). **MCP Apps** is the current MCP UI extension; **MCP-UI** is the community predecessor; OpenAI's Apps SDK surface now implements MCP Apps; **Open-JSON-UI** is a separate OpenAI declarative schema that AG-UI lists alongside A2UI/MCP-UI. ([MCP Apps](https://github.com/modelcontextprotocol/ext-apps); [AG-UI specs](https://docs.ag-ui.com/concepts/generative-ui-specs))
- **Protocol vs renderer.** MCP core is a tool/data protocol and has no UI. **MCP Apps** is the official UI extension (SEP-1865): tools declare `ui://` resources with `text/html;profile=mcp-app`, the host fetches and renders them in a **sandboxed iframe**, and the view talks to the host via **MCP JSON-RPC over `postMessage`**. It is a protocol extension *and* an HTML renderer contract. ([ext-apps](https://github.com/modelcontextprotocol/ext-apps); [SEP blog](https://blog.modelcontextprotocol.io/posts/2025-11-21-mcp-apps/))
- **Status.** The MCP Apps spec **2026-01-26 is stable** (Apache-2.0); SDK v2 exists; host support varies, with ChatGPT, Claude, VS Code, Goose, Postman, and others among the adopters. MCP-UI (community, Apache-2.0, 5.3k★, last push 2026-09-16) incubated the patterns and remains an iframe/web-component SDK. ([ext-apps](https://github.com/modelcontextprotocol/ext-apps); [MCP-UI](https://api.github.com/repos/MCP-UI-Org/mcp-ui))
- **OpenAI Apps SDK.** `developers.openai.com/apps-sdk` now resolves to OpenAI's **Plugins** docs. ChatGPT implements MCP Apps: `_meta.ui.resourceUri`, the `ui/*` bridge, CSP allowlists (`connectDomains`, `resourceDomains`, `frameDomains`), and optional `window.openai` extensions. This is an implementation of MCP Apps, not a separate native UI standard. ([OpenAI UI docs](https://developers.openai.com/plugins/build/chatgpt-ui))
- **Anthropic MCP.** MCP originated at Anthropic; MCP Apps was co-authored by OpenAI, Anthropic, and MCP-UI maintainers. Claude renders MCP Apps in sandboxed iframes. ([SEP blog](https://blog.modelcontextprotocol.io/posts/2025-11-21-mcp-apps/); [ext-apps](https://github.com/modelcontextprotocol/ext-apps))
- **Native-mobile applicability.** **None for MCP Apps/MCP-UI/OpenAI Apps SDK.** The spec supports only HTML iframes today and explicitly defers "native widgets" to future iterations. This app has no WebView, so these cannot render here. A2UI-over-MCP is a possible native path, but that requires a native A2UI renderer, not MCP Apps. ([SEP blog](https://blog.modelcontextprotocol.io/posts/2025-11-21-mcp-apps/); [inventory](docs/v2/V2-7-8-codebase-inventory-2026-11-22.md))
- **Security model.** Pre-declared templates the host can review, iframe sandboxing, CSP allowlists, auditable JSON-RPC messages, and host/user consent for UI-initiated tool calls. OpenAI additionally blocks privileged browser APIs and requires confirmation for irreversible actions. ([SEP blog](https://blog.modelcontextprotocol.io/posts/2025-11-21-mcp-apps/); [OpenAI security](https://developers.openai.com/plugins/guides/security-privacy))
- **Verdict: reject for rendering; reference-only for the security model.** The "closed templates + host consent + no ambient authority" posture is worth copying; the HTML/iframe transport is not.

## 5. Structured model output

| Provider | Mechanism | Guarantee | Return channel |
|---|---|---|---|
| **OpenAI** | Responses `text.format` with `type:"json_schema"`, `strict:true`; Chat Completions `response_format` | Constrained decoding; schema shape guaranteed for supported models; refusals/incomplete responses are separate | JSON in `output_text`; tool-calling variant puts structured args in tool calls |
| **OpenRouter** | Passes through `text.format` (Responses) or `response_format` (Chat); `strict` per request | **Varies by upstream endpoint**: some constrain, some translate, some treat as a hint; per-endpoint `structured_outputs` support | JSON text; streaming produces valid partial JSON |
| **Google Gemini** | `response_format: {type:"text", mime_type:"application/json", schema:{...}}` | Schema adherence; streaming supported; structured outputs + tools is a preview for Gemini 4/3 models | `output_text` |
| **Groq** | `response_format` with `json_schema`, `strict:true` or `false` | Strict = constrained decoding on listed models (gpt-oss-20b/120b, qwen3.8-27b, muse-spark-open); **streaming and tool use are not supported with Structured Outputs** | `choices[0].message.content` |
| **Anthropic** | `output_config.format` with `type:"json_schema"`; `strict:true` for tools | Stable; constrained decoding; SDKs can parse into typed objects | JSON in a **text content block** |
| **Firebase AI Logic** | `responseSchema` + `responseMimeType` (`application/json` or `text/x.enum`) | Schema subset (OpenAPI-ish); Kotlin/Android SDK; all fields required unless `optionalProperties` | `response.text` |

Sources: [OpenAI](https://developers.openai.com/api/docs/guides/structured-outputs), [OpenRouter](https://openrouter.ai/docs/features/structured-outputs), [Gemini](https://ai.google.dev/gemini-api/docs/structured-output), [Groq](https://console.groq.com/docs/structured-outputs), [Anthropic](https://platform.claude.com/docs/en/build-with-claude/structured-outputs), [Firebase AI Logic](https://firebase.google.com/docs/ai-logic/generate-structured-output). All six return structured data as **text content** (or as tool-call arguments when using function calling/tools); none has a separate native "structured object" channel.

- **Android/Firebase AI Logic.** Yes — the Firebase AI Logic Android SDK supports `responseSchema`/`responseMimeType` directly in Kotlin. This is a real Android structured-output path, but it is a **new provider SDK/backend**, not the current Crush/UDS architecture. ([Firebase](https://firebase.google.com/docs/ai-logic/generate-structured-output))
- **Through Crush without a new provider API: none per request.** Crush's local `POST /workspaces/{id}/messages` accepts `{session_id, prompt, run_id?, attachments?, channel?, hidden_user_message?}` — no `response_format`, schema, or "response type". Crush's `SelectedModel` has `provider_options`, and a provider has `extra_body`, but those are **global model/provider configuration**, not a per-turn schema; setting them would affect every chat turn. `crush run` JSON output is an open CLI feature request (#1034), not a server capability. ([local OpenAPI](spec/crush-openapi.json); [Crush schema](https://github.com/charmbracelet/crush/blob/main/schema.json); [issue #1034](https://github.com/charmbracelet/crush/issues/1034))
- **Consequence for V2-8.** A schema-constrained UI cannot be obtained through Crush today. The only available channels are (a) prompt-shaped JSON in an ordinary text part (best-effort), or (b) tool calls (blocked by the Chat deny-all boundary). Getting real structured output requires a new engine endpoint/capability or a direct provider call — the latter contradicts the current "local UDS only" architecture. ([inventory](docs/v2/V2-7-8-codebase-inventory-2026-11-22.md))
- **Also missing.** The app's model metadata is only `id/name/isFree/supportsImages`; it does not ingest context window, tool support, or structured-output support. Crush's config schema carries `context_window`, `default_max_tokens`, `can_reason`, and `supports_attachments`, but the local OpenAPI is lossy and the app has no read path for them. Adding that metadata is a smaller, separate engine/config task — not a new provider. ([Crush schema](https://github.com/charmbracelet/crush/blob/main/schema.json); [inventory](docs/v2/V2-7-8-codebase-inventory-2026-11-22.md))

## 6. Deterministic / generative UI prior art

- **A2UI.** Closed catalog + agent SDK compiler/validator + inference formats. Direct JSON is the baseline; **Express** is the default compact format (up to 3× fewer tokens / 3× lower latency); **Jev** is a decision model that chooses among pre-authored layouts (up to 80× faster, but only layouts the grammar describes). Rendering is native and catalog-bounded; generation is model-driven. ([A2UI 1.0 post](https://developers.googleblog.com/a2ui-10-build-delightful-fast-reliable-agentic-experiences/); [catalogs](https://a2ui.org/concepts/catalogs/))
- **json-render.** Closed catalog + Zod schemas + renderer validation; "predictable" and "schema every time" are vendor claims. No independent conformance suite was found in this research. ([repo](https://github.com/vercel-labs/json-render))
- **Vercel AI SDK `streamUI`.** AI SDK RSC is **experimental**; `streamUI` maps tool calls/generators to **ReactNode**. Not production-recommended, React-only, no Android. ([streamUI](https://ai-sdk.dev/docs/reference/ai-sdk-rsc/stream-ui))
- **Thesys C1.** Hosted LLM + React SDK that streams UI; docs now mark C1 as **legacy/archive** and direct new projects to OpenUI. No native Android renderer. ([C1 docs](https://docs.thesys.dev/legacy/guides/what-is-thesys-c1))
- **Claude artifacts.** Model-authored artifacts (documents, code, single-page sites, dashboards, interactive tools) rendered/run inside Claude's apps (with cloud code execution for artifact apps), shareable. It is a consumer product feature, not a schema-constrained API for third-party native apps. ([artifacts](https://support.claude.com/en/articles/9487310-what-are-artifacts-and-how-do-i-use-them))
- **OpenAI "UI generation" example.** Uses a recursive JSON Schema of allowed UI element types; guarantees the **shape** of the tree, not semantic correctness, data truth, or safe actions. ([OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs))
- **What actually guarantees what.** Provider constrained decoding guarantees **syntax/schema conformance** (and only on supported models/endpoints). Host validation guarantees **closed catalog / no unknown components**. Host-owned rendering and actions guarantee **no arbitrary code and no ambient authority**. Nothing in these sources guarantees semantic correctness, truthful data, accessible labels, or safe actions — those remain model-prompted or host-enforced.
- **Published failure modes.** OpenUI's vendor benchmark reports that **every tested format, including A2UI and json-render, produced incomplete generations**; its best pairing reached 99.5%, not 100% ([benchmark](https://www.openui.com/docs/openui-lang/reliability)). VGV's four layers are structural (unparseable), semantic (wrong component), data (fabricated values), and safety (jailbreak renders a forbidden UI) ([VGV](https://verygood.ventures/blog/generative-ui-hallucinations-four-layer-defense/)). InfoWorld argues model output must be treated as untrusted input and validated against a registry ([InfoWorld](https://www.infoworld.com/article/4210616/a-better-approach-to-generative-ui.html)). A 2026 evidence-mapping paper finds empirical support for declarative A2UI specifically is thin; most evidence concerns generated markup/components, and one cited survey found **55.8% of 1,000 LLM-generated e-commerce components contained at least one deceptive design** ([arXiv](https://arxiv.org/html/2604.16354)). EvoGenUI-Bench reports the strongest model at **74.9% turn pass but only 37.3% five-turn episode completion** ([ACL](https://aclanthology.org/2026.emnlp-main.1158/)).
- **Versioning/accessibility.** A2UI catalogs are versioned by `catalogId`, can register multiple versions, and carry `accessibility`/`checks` metadata; the Compose renderer maps them to semantics. That helps correctness, but a model can still choose the wrong component or write a misleading label. The other frameworks here are library-versioned rather than catalog-negotiated, so pin exact package versions. ([catalogs](https://a2ui.org/concepts/catalogs/); [release notes](https://developer.android.com/jetpack/androidx/releases/a2ui-compose))

## 7. Android-native examples

| Project | Activity | License | What it is | Production? |
|---|---|---|---|---|
| `androidx.a2ui.compose` (official) | `1.0.0-alpha02`, 2026-10-21 | Apache-2.0 | Compose renderer + Material 3 Basic Catalog; protocol 0.9.1 | **No** — alpha |
| [lmee/A2UI-Android](https://github.com/lmee/A2UI-Android) | 81★, pushed 2026-04-17, no releases | Apache-2.0 | Fork of `google/A2UI` + Compose renderer; claims API 21+, "production-ready", "v0.10" | **No** — stale; "v0.10" is not an official A2UI version; no releases |
| [BinaryTape/a2ui-kmp](https://github.com/BinaryTape/a2ui-kmp) | 51★, pushed 2026-11-22 | Apache-2.0 | KMP wrapper of AndroidX alpha02; Android/desktop/iOS; 0.9/0.9.1 only | **No** — third-party wrapper over alpha |
| [uny/a2ui-compose](https://github.com/uny/a2ui-compose) | 10★, pushed 2026-11-15 | Apache-2.0 | Compose Multiplatform renderer; 0.3.0; API explicitly unstable; CMP 1.12 floor | **No** — 0.x, forces toolchain upgrades |
| [JuneLeGency/ai-elements-kotlin](https://github.com/JuneLeGency/ai-elements-kotlin) | 24★, pushed 2026-11-09 | Apache-2.0 | Compose chat UI/agent toolkit over AG-UI/A2UI/MCP/A2A | **Demo/toolkit** |
| [coder-brzhang/a2ui-compose](https://github.com/coder-brzhang/a2ui-compose) | 10★, pushed 2026-04-28 | none | Claims A2UI "v0.10" Compose renderer | **Reject** — no license, stale |
| [TimothyySingh/a2ui-JetpackCompose](https://github.com/TimothyySingh/a2ui-JetpackCompose) | 8★, Feb 2026 | none | Demo | **Demo** |
| [JAICHANGPARK/droidkaigi2026-a2ui](https://github.com/JAICHANGPARK/droidkaigi2026-a2ui) | 5★, Sep 2026 | Apache-2.0 | DroidKaigi talk demo / AOSP reference | **Demo** |
| [NikhilBhutani/compose-genui](https://github.com/NikhilBhutani/compose-genui) | 4★, Feb 2026 | MIT | A2UI Compose demo | **Demo** |

A GitHub search for `a2ui compose` returned 52 repos; `json-render compose` returned 3, none Compose. The ecosystem is young and overwhelmingly demo-grade. Only the official AndroidX artifact has a credible maintenance path, and it is alpha. ([a2ui search](https://api.github.com/search/repositories?q=a2ui+compose); [json-render search](https://api.github.com/search/repositories?q=json-render+compose))

## 8. Security

- **Model output is untrusted input.** Prompt injection (direct and indirect) can steer component choice, data, labels, and actions. OWASP recommends constraining behavior, validating expected output formats with deterministic code, least privilege, human approval for high-risk actions, and segregating external content. ([OWASP LLM01](https://genai.owasp.org/llmrisk/llm01-prompt-injection/))
- **Fabricated data / dead controls.** A valid catalog component can still show invented prices, a nonfunctional button, or a misleading approval label. VGV documents structural, semantic, data, and safety failures; the fix is tools/context for real data, catalog prompts, and input/output guardrails — not just schema validation. ([VGV](https://verygood.ventures/blog/generative-ui-hallucinations-four-layer-defense/); [KZSoftworks](https://www.kzsoftworks.com/blog/generative-ui-under-control))
- **Action forgery.** A2UI actions are JSON messages (`name`, `surfaceId`, `sourceComponentId`, `context`); the renderer/host must map them to **host-owned commands**, not arbitrary tool/intent names. A2UI functions can declare `allowedCallers` (`rendererOnly`, `agentOnly`, `rendererOrAgent`), and `metadata.extensions` can carry attestations, but enforcement is the host's job. ([A2UI spec](https://a2ui.org/specification/v1.0-a2ui/); [catalogs](https://a2ui.org/concepts/catalogs/))
- **Data exfiltration.** Image URLs, `openUrl`, and deep links can encode data in query strings or navigate to attacker-controlled destinations. OWASP's indirect-injection example is exactly an image link that exfiltrates the conversation. The app's "no network beyond the UDS" posture is a strong default: do not wire an image loader or URL opener that can reach the network. ([OWASP](https://genai.owasp.org/llmrisk/llm01-prompt-injection/); [A2UI Compose media/url hooks](https://developer.android.com/develop/ui/compose/agentic/manage-catalogs))
- **Android specifics.** Prefer explicit intents; set `android:exported=false` unless required; validate all input in the receiver; intent filters are **not** security features. Reject dangerous URL schemes (`javascript:`, `data:`, `file:`) and allowlist `http(s)` hosts if any link handling is added. ([Android security tips](https://developer.android.com/privacy-and-security/security-tips); [MCP security](https://modelcontextprotocol.io/specification/2025-06-18/basic/security_best_practices))
- **MCP/iframe model (for reference).** Sandboxed iframe, pre-declared templates, CSP allowlists, auditable JSON-RPC, and consent for UI-initiated tool calls. OpenAI also blocks `window.alert/prompt/confirm`, clipboard, and nested frames by default. This is the right pattern if a WebView ever appears; it is not a reason to add one. ([MCP Apps](https://github.com/modelcontextprotocol/ext-apps); [OpenAI security](https://developers.openai.com/plugins/guides/security-privacy))
- **Versioning/accessibility.** Catalog IDs/versions and multiple registered versions prevent silent breakage; `accessibility`/`checks` metadata helps, but generated labels and layouts still need host review and TalkBack/font-scale/RTL tests. ([catalogs](https://a2ui.org/concepts/catalogs/); [release notes](https://developer.android.com/jetpack/androidx/releases/a2ui-compose))
- **Chat deny-all.** Keep it. Any generated UI action must be a host command (navigation, copy, open settings, etc.), never an engine tool call or a permission grant. ([inventory](docs/v2/V2-7-8-codebase-inventory-2026-11-22.md))
- **Mitigation set for this app.** Closed allowlisted block catalogue; no HTML/JS/WebView; strict JSON schema/size/depth validation with text fallback; host-owned action map with confirmation gates for irreversible or state-changing actions; no network-capable image/URL handlers; no permission or tool invocation from generated UI; TalkBack/font-scale/RTL tests before shipping.

## Verdicts for this app

| Item | Verdict | Why |
|---|---|---|
| AndroidX A2UI Compose dependency | **Reference-only / experiment-only** | Native Compose and a closed catalog, but alpha, protocol 0.9.1, and `minCompileSdk 37.1`; conflicts with stable-dependency posture |
| A2UI protocol concepts (catalog, surface, data model, host renderer) | **Adoptable as design reference** | Exactly the right trust boundary; no dependency required |
| Vercel json-render | **Reference-only** | No Compose/Android renderer; TS framework |
| CopilotKit / OpenIntelligentUI | **Reject** | Web/React Native/iframe architecture; no native Compose |
| AG-UI | **Reference-only** | Transport protocol, no renderer; Crush does not speak it |
| MCP Apps / MCP-UI / OpenAI Apps SDK | **Reject for rendering** | HTML iframe/WebView only; native widgets deferred |
| MCP Apps security model | **Reference-only** | Predeclared templates, sandbox, CSP, consent, audit |
| Structured outputs through Crush | **Reject today** | No per-turn `response_format`; config-only `provider_options`; CLI JSON request is open |
| Host-owned typed UI blocks + closed registry | **Adoptable** | Deterministic, testable, fits no-WebView/no-network/deny-all constraints |
| Prompt-only JSON UI | **Reject as a "deterministic" foundation** | Best-effort; no schema guarantee, no safe-action guarantee |
| Thesys C1 / Claude artifacts | **Reject** | Hosted/consumer products; no native Android embed path |

## Proposed V2-7 / V2-8 split (PROPOSAL — not owner intent)

**V2-7 — deterministic intelligent UI foundation (no external UI framework dependency).**
1. Fix the inventory gap: unknown message parts must render as text/fallback, not disappear (acceptance kit row 6).
2. Define a host-owned `ui_block/v1` model: a small closed set (text, code, table, key-value, callout, divider, host actions) with a strict JSON envelope in a normal text part. Versioned; unknown block types/fields fall back to text. No HTML, JS, URLs, or tool calls. V2-7 ships only host-authored/static instances; model-authored instances are a V2-8 question.
3. Build a deterministic Compose renderer + validator: depth/size limits, unknown-field rejection, stable replacement on whole-message republish, accessibility semantics, dark theme, font scaling, RTL.
4. Do **not** add A2UI/json-render as a V2-7 dependency. Copy only the catalog/registry/validation ideas.
5. Optional, separate: ingest richer model metadata from Crush's existing config if a read path exists; do not add a provider.

**V2-8 — structured model UI experiment (debug-flag only, not shipping).**
1. Gate behind a debug/internal flag; read-only surfaces first. No actions, no media, no `openUrl`, no network, no WebView, no tool affordances.
2. Compare three candidates on the same prompts: (a) prompt-only `ui_block/v1`, (b) AndroidX A2UI `1.0.0-alpha02` in an isolated module with a tiny Basic Catalog subset, (c) a json-render-style spec translated by a local Compose adapter (no JS dependency).
3. Record: parse/validate success, fallback rate, render time on the Redmi Note 7 Pro, TalkBack/font-scale/RTL results, token cost, and a failure taxonomy (structural/semantic/data/safety).
4. Exit criteria: A2UI protocol 1.0 support + at least beta API + a measurable UX win + acceptable compileSdk/toolchain cost. Otherwise keep V2-7 blocks and close V2-8 as "not adopted".
5. Do not let the experiment change the Chat deny-all boundary or add a WebView "just for the demo".

## Open questions needing owner input

1. Does V2-7 allow **any** model-authored UI, or only deterministic rendering of host-authored blocks? The name suggests a foundation, but there is no brief.
2. Is a new **per-request** Crush endpoint/capability for structured output acceptable, or is "no new provider API" a hard constraint?
3. Is an **alpha** AndroidX dependency acceptable behind a debug flag, or is the stable-only posture absolute?
4. Which surfaces are in scope: Chat only, or also Code/Projects? Is generated UI ever interactive?
5. If interactive: who authorizes actions, what confirmation UX is required, and which commands are permanently forbidden?
6. What is the accessibility/i18n bar: WCAG level, TalkBack, RTL, font scaling, localization of generated labels?
7. Do we need **A2UI wire compatibility** with external agents, or is an internal block format enough? This decides whether to track the protocol at all.
8. What is the timebox and kill criteria for V2-8, and what telemetry (if any) may log generated payloads?
9. Is WebView absolutely excluded for V2-8? (Current inventory says no WebView; confirm it is a hard boundary.)
10. What is the fallback when generated UI is invalid or unsafe: plain text, an error card, or a retry? Who owns that decision?

## Contradictions, gaps, and confidence notes

- **A2UI 1.0 vs AndroidX 0.9.1 is a support gap, not a factual contradiction.** The protocol is at 1.0; the only official Compose renderer is still 0.9.1. Do not describe `1.0.0-alpha02` as "A2UI 1.0 support".
- **"1.0 current" vs "1.0 candidate" is a genuine source conflict.** a2ui.org's `versions.json` and Google's 2026-11-11 post call v1.0 current; CopilotKit's generative-UI docs (spec revision `cbaa5ee`, October 2026) still say "upstream still calls 1.0 a candidate". The likeliest reading is that CopilotKit's callout predates the 1.0 announcement; verify against the spec repo before making compatibility claims. ([a2ui.org versions](https://raw.githubusercontent.com/a2ui-project/a2ui/main/specification/versions.json); [CopilotKit docs](https://docs.copilotkit.ai/agent-spec/generative-ui/components))
- **"Production-ready" claims are not evidence.** `lmee/A2UI-Android` claims production readiness but has no releases, has been inactive since 2026-04-17, and refers to a non-existent official "v0.10". Treat it as a fork/demo.
- **OpenAI Apps SDK naming is transitional.** The old `apps-sdk` URL resolves to Plugins docs; ChatGPT now implements MCP Apps. Cite the Plugins/MCP Apps docs, not the old name.
- **Thesys C1 is legacy.** Its own docs archive C1 and recommend OpenUI for new projects; do not present C1 as the current path.
- **Vendor benchmarks are directional.** OpenUI's benchmark is run by OpenUI; it is useful for failure modes, not as an independent ranking.
- **A2UI-specific evidence is thin.** The 2026 mapping paper explicitly says most empirical evidence concerns generated markup/components, not A2UI's declarative protocol; treat A2UI safety claims as design arguments, not proven results.
- **AndroidX issue counts were not retrievable** without Google sign-in; the release page/issue-tracker component is cited, but no open-issue count is asserted.
- **Star/open-issue counts are point-in-time GitHub metadata**, not quality or production-readiness signals; they are used only to show activity and ecosystem size.
