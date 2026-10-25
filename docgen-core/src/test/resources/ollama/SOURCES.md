# Respostas reais do Ollama usadas nos testes

Capturadas em 2026-10-25 do Ollama 0.32.11 em `127.0.0.1:11434` (Linux, RTX 5070 de 12 GB).

| Arquivo | Origem |
|---|---|
| `tags.json` | `GET /api/tags` com um modelo remoto de teste (`docgen-probe-remote:latest`), criado só para esta captura com `POST /api/create {"from":"qwen2.5:14b","remote_host":"http://127.0.0.1:9"}` e apagado em seguida. Um `create` remoto só grava um manifesto local: nenhum tráfego sai da máquina. |
| `show-remote-stub.json` | `POST /api/show` desse modelo remoto (sem `modelfile` e `template`). |
| `show-qwen2.5-14b.json` | `POST /api/show` do `qwen2.5:14b`, aparado: sem a lista de tensores, o texto da licença, o Modelfile e os campos nulos. |
| `ps.json` | `GET /api/ps` com o `llama3.2:latest` carregado (`num_ctx` 4096, 100% na GPU). |
| `chat-stream.ndjson` | `POST /api/chat` com `stream: true`, `llama3.2:latest`, `num_predict: 8`. |
| `pull-partial.ndjson` | Trechos de `POST /api/pull` do `qwen3:0.6b`, cancelado no meio (o download foi retomado de onde parou na chamada seguinte). |
| `pull-error.ndjson` | `POST /api/pull` de um nome inexistente: o erro chega **no meio do stream, com HTTP 200**. |
