"""Chinese clause retrieval with a shared plan/date scope for both channels."""
import argparse
import hashlib
import json
import os
from collections import defaultdict
from datetime import date
from functools import lru_cache
from pathlib import Path

import jieba
from fastembed import TextEmbedding
from qdrant_client import QdrantClient, models
from rank_bm25 import BM25Okapi

MODEL = "BAAI/bge-small-zh-v1.5"
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："
CORPUS = Path(__file__).with_name("clauses.json")


def tokens(text: str) -> list[str]:
    return [word.lower() for word in jieba.cut_for_search(text) if any(map(str.isalnum, word))]


@lru_cache(maxsize=16)
def bm25_index(clauses: tuple[str, ...]) -> BM25Okapi:
    return BM25Okapi([tokens(text) for text in clauses])


class Knowledge:
    def __init__(self):
        raw = CORPUS.read_bytes()
        self.clauses = json.loads(raw)
        # A revision in the collection name keeps stale chunks out after corpus changes.
        self.collection = "ticketflow_bge_" + hashlib.sha256(raw).hexdigest()[:12]
        endpoint = os.getenv("QDRANT_URL")
        self.client = QdrantClient(url=endpoint, timeout=8) if endpoint else QdrantClient(":memory:")
        self.embed = TextEmbedding(
            model_name=MODEL,
            cache_dir=os.getenv("TICKETFLOW_EMBED_CACHE", ".local/models"),
            threads=2,
        )

    def index(self):
        if not self.client.collection_exists(self.collection):
            self.client.create_collection(
                self.collection,
                vectors_config=models.VectorParams(
                    size=self.embed.get_embedding_size(MODEL), distance=models.Distance.COSINE
                ),
            )
        if self.client.count(self.collection, exact=True).count == len(self.clauses):
            return
        vectors = self.embed.passage_embed([f"{row['title']} {row['text']}" for row in self.clauses])
        self.client.upsert(self.collection, [
            models.PointStruct(id=index, vector=vector.tolist(), payload=row)
            for index, (row, vector) in enumerate(zip(self.clauses, vectors, strict=True))
        ])

    def search(self, query: str, plan: str | None, *, mode: str = "hybrid", limit: int = 5,
               as_of: date | None = None) -> list[dict]:
        today = (as_of or date.today()).toordinal()
        scoped_plans = ["ALL"] + ([plan] if plan else [])
        scoped = [row for row in self.clauses
                  if row["plan"] in scoped_plans and row["start"] <= today <= row["end"]]
        sparse, dense = [], []
        if scoped and mode != "dense":
            index = bm25_index(tuple(f"{row['title']} {row['text']}" for row in scoped))
            scores = index.get_scores(tokens(query))
            scored = sorted(zip(scoped, scores, strict=True), key=lambda hit: -hit[1])
            sparse = [row for row, score in scored[:10] if score > 0]
        if mode != "bm25":
            filters = models.Filter(must=[
                models.FieldCondition(key="plan", match=models.MatchAny(any=scoped_plans)),
                models.FieldCondition(key="start", range=models.Range(lte=today)),
                models.FieldCondition(key="end", range=models.Range(gte=today)),
            ])
            vector = next(self.embed.embed([QUERY_PREFIX + query])).tolist()
            dense = [point.payload for point in self.client.query_points(
                self.collection, query=vector, query_filter=filters, limit=10,
            ).points]
        if mode == "bm25":
            return sparse[:limit]
        if mode == "dense":
            return dense[:limit]
        scores = defaultdict(float)
        hits = {}
        for channel in (sparse, dense):
            for position, row in enumerate(channel, 1):
                hits[row["id"]] = row
                scores[row["id"]] += 1 / (60 + position)
        return [hits[key] for key in sorted(scores, key=lambda key: -scores[key])[:limit]]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="索引 TicketFlow 知识条款")
    parser.add_argument("--index", action="store_true", required=True)
    parser.parse_args()
    knowledge = Knowledge()
    knowledge.index()
    print(f"{knowledge.collection}: {len(knowledge.clauses)} clauses")
