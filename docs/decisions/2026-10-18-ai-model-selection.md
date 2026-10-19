# Available AI models and workspace choices

Status: approved by the owner's request on 2026-10-18; implemented.

The owner replaced the local embedding model with `embeddinggemma-2:270m` and
requested UI choices among models available from the configured providers. This
permits durable per-capability model/profile choices while endpoint URLs, provider
credentials, rate limits and operator embedding-prefix overrides remain environment
configuration. It narrowly supersedes the original environment-only model choice;
historical approved documents 01–05 remain unchanged.

Each SQLite library or hosted workspace stores its choices in the existing setting
table. Only its owner may manage those choices; browser writes require the matching
Origin. Editors/viewers cannot discover inventory or select models. Profile objects
are immutable; each gateway builds its own effective overlay so one user's choices
cannot mutate another user's provider configuration. Restarts and scoped operator
commands load the same saved choices. A removed operator profile leaves only the
affected selected capability unavailable and its choice inspectable/resettable.

The server queries only operator-configured provider origins, with redirects off,
bounded response sizes/model counts/timeouts and limited metadata concurrency.
Ollama inventory uses native tags/show beside its configured `/v1` endpoint.
Generic OpenAI-compatible providers use their configured models endpoint when
native metadata is absent or unsupported; unknown compatibility stays explicit.
Anthropic uses its native model list. No arbitrary URLs, downloads or raw provider
prompts/configuration enter the model-picker response.

Saving uses a revision tied to both the saved choice and public operator
configuration. Changed choices are checked against fresh inventory; failed checks
preserve the previous choice. Fixed-content embedding probes determine dimensions
and reject invalid/zero vectors before persistence. New embedding descriptors and
generation fences prevent mixing spaces or late model results; a new target
backfills before activation, retaining old bytes for coherent available old-model
queries. Missing historical providers cannot block replacement backfill. Selection
does not discard the old index or alter plugin consent and note eligibility.

EmbeddingGemma 2's official [model configuration](https://huggingface.co/google/embeddinggemma-2/raw/main/config_sentence_transformers.json)
defines retrieval query prefix `task: search result | query: ` and untitled document
prefix `title: none | text: `. These match the prior model's retrieval convention;
output dimensions still come from the served model's fixed-content probe. Google
documents [different query/document prompts](https://developers.googleblog.com/embeddinggemma-2-the-developer-guide/)
and configurable output dimensions. Serving multimodal embeddings does not imply
that the model can generate image text through a chat/vision endpoint.

Provider contracts: [Ollama model lists](https://docs.ollama.com/api/tags),
[Ollama API schema](https://github.com/ollama/ollama/blob/main/docs/openapi.yaml)
and [Anthropic model lists](https://platform.claude.com/docs/en/api/models/list).
The current API and failure semantics are in [AI contracts](../api.md#m4-ai-contracts).
