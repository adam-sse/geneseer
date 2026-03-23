# Setup the chromadb-worker

1. Copy this directory to somewhere on the machine that will run geneseer, well call this directory `$chromadbWorkerDir`
2. Create a venv: `python -m venv .venv`
3. Activate the venv for the current shell: `source .venv/bin/activate`
4. Install dependencies: `pip install --upgrade pip && pip install chromadb ollama`
5. Set the following options when running geneseer:
    * `--config.rag.chromadbWorkerPythonBinaryPath $chromadbWorkerDir/.venv/bin/python`
    * `--config.rag.chromadbWorkerPath $chromadbWorkerDir/chromadb-worker.py`
