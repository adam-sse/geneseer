#!/usr/bin/env python3

import sys
import argparse
import json
import chromadb
import ollama

@chromadb.utils.embedding_functions.register_embedding_function
class OllamaEmbeddingFunction(chromadb.EmbeddingFunction):
    def __init__(self, model, host):
        self.model = model
        self.client = ollama.Client(host=host)

    def __call__(self, input):
        if isinstance(input, str):
            input = [input]
        response = self.client.embed(
            model=self.model,
            input=input
        )
        return response["embeddings"]

class MethodStore:
    def __init__(self, model, host):
        self.client = chromadb.PersistentClient(f"./geneseer-chromadb-{model}")
        self.embedding_function = OllamaEmbeddingFunction(model=model, host=host)
        self.init_collection()
        self.next_id = 1

    def init_collection(self):
        self.collection = self.client.get_or_create_collection(
            name="methods",
            embedding_function=self.embedding_function
        )

    def add_entry(self, data):
        ids = []
        documents = []
        metadatas = []
        for method in data.get("methods"):
            ids += [f"method_{self.next_id}"]
            documents += [method.get("code")]
            metadatas += [{
                "signature": method.get("signature"),
                "file": method.get("file"),
            }]
            self.next_id += 1

        batch_size = self.client.get_max_batch_size() or 128
        for i in range(0, len(ids), batch_size):
            self.collection.add(
                ids=ids[i:i+batch_size],
                documents=documents[i:i+batch_size],
                metadatas=metadatas[i:i+batch_size]
            )
        return {"status": "ok", "ids": ids}

    def query_entries(self, data):
        prompt = data.get("prompt", [])
        n_results = data.get("n_results", 5)
        results = self.collection.query(
            query_texts=[prompt],
            n_results=n_results
        )
        results["status"] = "ok"
        return results

    def clear(self):
        self.client.reset()
        self.init_collection()
        self.next_id = 1
        return {"status": "ok"}

    def handle_command(self, data):
        action = data.get("action")

        if action == "add":
            return self.add_entry(data)
        elif action == "query":
            return self.query_entries(data)
        elif action == "entry_count":
            return {"status": "ok", "entry_count": self.collection.count()}
        elif action == "clear":
            return self.clear()
        else:
            return {"status": "error", "error": f"Unknown action '{action}'"}

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Embedding model")
    parser.add_argument("--host", default="http://localhost:11434", help="Ollama host URL")
    return parser.parse_args()

def main():
    args = parse_args()
    store = MethodStore(model=args.model, host=args.host)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            data = json.loads(line)
            output = store.handle_command(data)

        except json.JSONDecodeError:
            output = {"status": "error", "error": "Invalid JSON"}
            continue

        except Exception as e:
            output = {
                "status": "error",
                "error": str(e),
                "type": type(e).__name__
            }

        print(json.dumps(output), flush=True)

if __name__ == "__main__":
    main()
