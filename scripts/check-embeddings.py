#!/usr/bin/env python3
"""Reproduce M4 retrieval using synthetic fixtures and local embedding models only."""

import argparse
import hashlib
import json
import math
import os
import platform
import statistics
import time
from datetime import UTC, datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
MODELS = {
    "embeddinggemma": ("task: search result | query: ", "title: none | text: "),
    "bge-m3": ("", ""),
    "nomic-embed-text": ("search_query: ", "search_document: "),
}


def sha(value):
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()


def call(base, route, body=None):
    data = None if body is None else json.dumps(body).encode()
    # main restricts the endpoint to HTTP loopback.
    request = Request(
        base + route, data=data, headers={"Content-Type": "application/json"}
    )
    with urlopen(request, timeout=180) as response:
        value = json.load(response)
    if value.get("remote_host") or value.get("remote_model"):
        raise ValueError("Cloud inference is outside this synthetic local benchmark.")
    return value


def embedding(base, model, text):
    start = time.perf_counter()
    value = call(
        base,
        "/api/embed",
        {
            "model": model,
            "input": text,
            "truncate": False,
            "keep_alive": "10m",
            "options": {"num_gpu": 0},
        },
    )
    elapsed = (time.perf_counter() - start) * 1000
    vector = value["embeddings"][0]
    if not vector or any(
        type(x) not in {int, float} or not math.isfinite(x) for x in vector
    ):
        raise ValueError("Invalid embedding values.")
    norm = math.sqrt(sum(x * x for x in vector))
    if not norm:
        raise ValueError("Zero embedding.")
    return [x / norm for x in vector], elapsed


def latency(values):
    ordered = sorted(values)
    return {
        "count": len(values),
        "p50_ms": round(statistics.median(values), 3),
        "p95_ms": round(ordered[math.ceil(len(values) * 0.95) - 1], 3),
        "mean_ms": round(statistics.mean(values), 3),
    }


def evaluate(base, model, fixtures, tag, repeats):
    query_prefix, document_prefix = MODELS[model]
    docs, queries = fixtures["documents"], fixtures["queries"]
    # Explicit warmup excludes model load from the warm per-document timings.
    _, cold = embedding(base, model, document_prefix + "Synthetic warmup passage.")
    runner = next(
        row
        for row in call(base, "/api/ps")["models"]
        if row["name"].removesuffix(":latest") == model
    )
    if runner.get("size_vram", 0) != 0:
        raise ValueError("CPU-only benchmark unexpectedly loaded GPU memory.")
    vectors, doc_times = [], []
    for doc in docs:
        vector, elapsed = embedding(base, model, document_prefix + doc["body"])
        vectors.append(vector)
        doc_times.append(elapsed)
    records, query_times = [], []
    for query in queries:
        measured = []
        for _ in range(repeats):
            vector, elapsed = embedding(base, model, query_prefix + query["text"])
            measured.append(elapsed)
            query_times.append(elapsed)
        if any(len(vector) != len(doc_vector) for doc_vector in vectors):
            raise ValueError("Embedding dimensions disagree.")
        ranked = sorted(
            [
                (doc["id"], sum(a * b for a, b in zip(vector, doc_vector, strict=True)))
                for doc, doc_vector in zip(docs, vectors, strict=True)
            ],
            key=lambda item: (-item[1], item[0]),
        )
        relevant = set(query["relevant"])
        cross = {
            doc["id"]
            for doc in docs
            if doc["id"] in relevant and doc["language"] != query["language"]
        }
        records.append(
            {
                "query_id": query["id"],
                "language": query["language"],
                "top5": [
                    {"id": key, "score": round(score, 8)} for key, score in ranked[:5]
                ],
                "recall3": len(relevant & {key for key, _ in ranked[:3]})
                / len(relevant),
                "recall5": len(relevant & {key for key, _ in ranked[:5]})
                / len(relevant),
                "cross_language_recall3": len(cross & {key for key, _ in ranked[:3]})
                / len(cross),
                "query_latency_ms": [round(value, 3) for value in measured],
            }
        )
    groups = {}
    for lang in ("all", "en", "it"):
        selected = [row for row in records if lang == "all" or row["language"] == lang]
        groups[lang] = {
            key: round(statistics.mean(row[key] for row in selected), 6)
            for key in ("recall3", "recall5", "cross_language_recall3")
        }
    info = call(base, "/api/show", {"model": model})
    if "embedding" not in info.get("capabilities", []):
        raise ValueError("Selected model is not embedding capable.")
    result = {
        "model": model,
        "runner": runner,
        "digest": tag["digest"],
        "size_bytes": tag["size"],
        "dimension": len(vectors[0]),
        "query_prefix": query_prefix,
        "document_prefix": document_prefix,
        "cold_warmup_ms": round(cold, 3),
        "document_latency": latency(doc_times),
        "query_latency": latency(query_times),
        "metrics": groups,
        "model_details": tag.get("details"),
        "model_info": info.get("model_info"),
        "document_vectors_sha256": sha(vectors),
        "queries": records,
    }
    result["ranking_sha256"] = sha(
        [{k: row[k] for k in ("query_id", "top5")} for row in records]
    )
    call(base, "/api/embed", {"model": model, "input": [], "keep_alive": 0})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:11434")
    parser.add_argument(
        "--fixtures", type=Path, default=ROOT / "spec/fixtures/embedding-retrieval.json"
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--models", nargs="+", choices=tuple(MODELS), default=list(MODELS)
    )
    parser.add_argument("--repeats", type=int, choices=range(1, 6), default=3)
    args = parser.parse_args()
    parts = urlsplit(args.url)
    if (
        parts.scheme != "http"
        or parts.hostname not in {"127.0.0.1", "localhost", "::1"}
        or parts.path
    ):
        parser.error("Use a plain HTTP loopback Ollama endpoint without a path.")
    fixtures = json.loads(args.fixtures.read_text())
    if len(fixtures["queries"]) != 30 or any(
        sum(query["language"] == lang for query in fixtures["queries"]) != 15
        for lang in ("en", "it")
    ):
        raise ValueError(
            "The fixture contract is thirty queries: fifteen English and fifteen Italian."
        )
    tags = {
        row["name"].removesuffix(":latest"): row
        for row in call(args.url, "/api/tags")["models"]
    }
    if any(model not in tags for model in args.models):
        parser.error(
            "Pull the selected local embedding models before running this check."
        )
    report = {
        "created_at": datetime.now(UTC).isoformat(),
        "scope": "synthetic fixtures only",
        "fixture_sha256": hashlib.sha256(args.fixtures.read_bytes()).hexdigest(),
        "fixture_documents": len(fixtures["documents"]),
        "fixture_queries": len(fixtures["queries"]),
        "ollama_version": call(args.url, "/api/version")["version"],
        "platform": platform.platform(),
        "machine": platform.machine(),
        "logical_cpus": os.cpu_count(),
        "conditions": {
            "num_gpu": 0,
            "query_repeats": args.repeats,
            "truncate": False,
            "ranking": "exact normalized cosine; no similarity floor, FTS or reranker",
            "timings": "sequential local HTTP wall time; warm, excluding explicit first warmup",
        },
        "models": [],
    }
    for model in args.models:
        result = evaluate(
            args.url.rstrip("/"), model, fixtures, tags[model], args.repeats
        )
        report["models"].append(result)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(
            json.dumps(
                {
                    "model": model,
                    "metrics": result["metrics"],
                    "query_latency": result["query_latency"],
                }
            ),
            flush=True,
        )
    print("Report:", args.output)


if __name__ == "__main__":
    try:
        main()
    except (HTTPError, URLError, ValueError, KeyError) as error:
        raise SystemExit("Synthetic embedding check failed: " + str(error)) from None
