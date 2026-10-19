# M4 synthetic retrieval comparison

This check uses only invented notes and questions. It compares the three D17 candidates locally and does not establish retrieval quality for the owner's library. No owner database, Blinko export, chat model or cloud model was used.

## Reproduction and evidence

The [script](../../scripts/check-embeddings.py) uses Python's standard library and Ollama's local embedding API. The [fixture](../../spec/fixtures/embedding-retrieval.json) defines 60 short notes: 15 topics, an English and Italian relevant note for each topic, and two close distractors per topic. Each topic has one English and one Italian query, for exactly 30 queries (15 EN, 15 IT). Each query has two relevant IDs, one in each language, declared before inference. Topics include loose ideas, gardening, reading, fictional trips and notes-system behavior.

```sh
ollama pull embeddinggemma
ollama pull bge-m3
ollama pull nomic-embed-text
python3 scripts/check-embeddings.py \
  --url http://127.0.0.1:11434 \
  --output docs/development/M4-retrieval-results.json
```

The script refuses non-loopback endpoints and models outside these three names. It checks embedding capability, finite vectors and dimensions; records full model digests, model metadata, fixture hash, vector hash and ranking hash; requests `num_gpu=0`, and verifies the selected runner reports zero GPU memory. It unloads only the selected embedding model after its measurements. Downloading these models added about 2.05 decimal GB; the pre-existing local/chat/cloud model entries were retained and no existing model configuration was changed.

Each document is embedded once after a separate cold warmup. Each query is measured three times sequentially on the warm model; the final vector supplies its ranking. Timings are local HTTP wall times, including tokenization and embedding. Query caching in Spunto, batching, network deployment, FTS, RRF, similarity floors and reranking are excluded. Normalized exact cosine ranks the whole corpus. Recall@k is the mean fraction of each query's two relevant IDs present in its first k results; cross-language Recall@3 measures the relevant note in the other language.

The retrieval prefixes follow the model authors' current instructions: EmbeddingGemma uses `task: search result | query: ` and `title: none | text: ` ([Google model card](https://huggingface.co/google/embeddinggemma-300m)); Nomic uses `search_query: ` and `search_document: ` ([Nomic model card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5)). BGE-M3 receives plain text, as in its dense-retrieval usage examples ([BAAI model card](https://huggingface.co/BAAI/bge-m3)). Embedding requests use the documented `/api/embed` endpoint with `truncate=false` ([Ollama API](https://docs.ollama.com/api/embed)). Prefixes are part of the model fingerprint; changing them requires reindexing.

Measured on 2026-10-14 with Ollama 0.40.4 on macOS 27.0.1, Apple M4 Pro (Mac16,8), 14 logical CPUs and 48 GiB RAM. The canonical machine-readable report is [M4-retrieval-results.json](M4-retrieval-results.json). The source fixture SHA-256 is `58bafa2e88f94daf3ad30369f7e9a5341788db40dcf6407f06ecfda73913557c`.

## Results

| Model | Dimension | Model size (MB) | R@3 | R@5 | Cross-language R@3 | Query p50 / p95 (ms) | Document p50 / p95 (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|
| embeddinggemma | 768 | 621.9 | 100.00% | 100.00% | 100.00% | 202.3 / 712.2 | 204.5 / 597.5 |
| bge-m3 | 1024 | 1157.7 | 98.33% | 100.00% | 96.67% | 56.8 / 319.9 | 191.6 / 1612.2 |
| nomic-embed-text | 768 | 274.3 | 60.00% | 63.33% | 20.00% | 34.5 / 327.8 | 190.4 / 1114.7 |

| Model | EN R@3 / R@5 | IT R@3 / R@5 | EN cross R@3 | IT cross R@3 |
|---|---:|---:|---:|---:|
| embeddinggemma | 100.00% / 100.00% | 100.00% / 100.00% | 100.00% | 100.00% |
| bge-m3 | 96.67% / 100.00% | 100.00% / 100.00% | 93.33% | 100.00% |
| nomic-embed-text | 70.00% / 76.67% | 50.00% / 50.00% | 40.00% | 0.00% |

BGE-M3’s only top-three miss was `bike-q-en`: it ranked the Italian relevant bicycle note fourth, behind two brake-related distractors. Nomic missed the opposite-language relevant note on all 15 Italian queries.

The host concurrently ran other local M4 verification, so these latency observations are not an isolated throughput benchmark. A second measurement preserved every recall result; its timing variation illustrates the shared-host noise. The report is the final run, with zero GPU memory recorded for every selected runner.

Full model digests:

- `embeddinggemma`: `85462619ee721b466c5927d109d4cb765861907d5417b9109caebc4e614679f1`; ranking SHA-256 `426a64b78855e0d25807305733811078d7f43324a8de586ea3e3113f3099a1b5`.
- `bge-m3`: `7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`; ranking SHA-256 `f6b9016f43224852b29b431e1d2c0230decab9167d4792fab042633f6302b0a9`.
- `nomic-embed-text`: `0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f`; ranking SHA-256 `4d73c7bb3aab41d73e49a94e037c09cb8f539792d59b7f0d604961c91d32f60b`.

Final report SHA-256: `38eca88ad85461db936daf6bd3af3625ad32a38801eb49504bb9653b91974039`.

## D17 assessment

EmbeddingGemma supports D17 as the default **for this synthetic check**: it retrieved both language versions within the first three results for every query and uses roughly half BGE-M3's model storage. BGE-M3 also performed well, reaching full Recall@5, and its measured query latency was lower here. The speed result does not support calling EmbeddingGemma the fastest CPU candidate. Nomic's poor cross-language recall makes it a weaker default for this bilingual fixture.

The fixture is small, manually written, balanced across languages and intentionally includes translated relevant pairs. It does not represent real note lengths, vocabulary, ambiguity or topic frequency. Thirty queries cannot establish a broad model ranking; a larger independently judged bilingual dataset could change the comparison. The owner-data evaluation described in the approved specification was explicitly replaced by synthetic fixtures for this local milestone, so no quality claim is made about an imported or live library.
