# ADR 0004: Catálogo Qwen e recomendação de modelos por hardware

| | |
|---|---|
| Estado | **Proposta, implementada na Fase 3** (aguarda aprovação junto com a fase) |
| Data | 2026-10-25 |
| Fase | 3 |
| Relacionadas | [ADR 0003](0003-somente-local.md), [roadmap](../desktop/ROADMAP.md) |

## Contexto

O DocGen precisa dizer, para cada computador, quais modelos Qwen ele aguenta, sem prometer o que não mediu.
Recomendar só pela VRAM nominal erra nos dois sentidos: ignora o cache que cresce com o contexto e confia num
número que, sem o driver, pode nem ser a memória da placa (medido aqui: o `lspci` mostra 288 MB de janelas de
memória numa RTX 5070 de 12 GB). Os dados dos modelos também envelhecem rápido: a família `qwen3.5` foi
atualizada na biblioteca do Ollama uma semana antes desta fase.

## Decisão

### 1. Catálogo versionado, fonte única e lista de permitidos

`docgen-api/src/main/resources/models/qwen-catalog.json` (esquema 1, verificado em 2026-10-25) guarda, por tag:
família, parâmetros totais e ativos, quantização, tamanho de download, contexto máximo, arquitetura (camadas,
camadas com atenção completa, cabeças KV, dimensão da cabeça, estado fixo), finalidades, modo de raciocínio,
visão, descrição em PT-BR e a evidência da verificação. O código não repete esses dados.

É também a camada 1 do ADR 0003: o app só baixa, testa e aceita como padrão essas tags. O arquivo é validado ao
carregar (tag de biblioteca sem host nem `cloud`, sem duplicatas, números coerentes), e um catálogo quebrado
impede o app de subir em vez de recomendar errado.

**16 tags, todas verificadas** (`"verified": true`):

| Finalidade | Tags |
|---|---|
| Uso geral | `qwen3:0.6b`, `qwen3:1.7b`, `qwen3:4b-instruct`, `qwen3:8b`, `qwen3:14b`, `qwen3:30b-instruct`, `qwen3.5:2b`, `qwen3.5:4b`, `qwen3.5:9b` |
| Raciocínio | `qwen3:8b`, `qwen3:14b`, `qwen3:32b`, `qwen3.5:9b` |
| Código | `qwen2.5-coder:1.5b`, `qwen2.5-coder:3b`, `qwen2.5-coder:7b`, `qwen2.5-coder:14b`, `qwen2.5-coder:32b`, `qwen3-coder:30b` |

Fora do catálogo, de propósito:

- `qwen3:4b` e `qwen3:30b`: as tags padrão apontam para as versões `thinking-2507`, que só respondem depois de
  raciocinar. Entram as `instruct-2507` (`qwen3:4b-instruct`, `qwen3:30b-instruct`), que o cartão oficial
  descreve como "supports only non-thinking mode".
- Modelos acima de ~20 GB de download (`qwen3:235b`, `qwen3-coder:480b`, `qwen3.5:27b` e maiores) e as
  famílias `qwen3.6`/`qwen3.8`: fora do alcance da maioria das máquinas; entram quando houver pedido.

Como os dados foram conferidos:

- **Biblioteca do Ollama** (páginas de tags e de cada tag): tamanho, contexto e digest. A quantização padrão foi
  confirmada pelo digest igual ao da variante `q4_K_M`, ou pela página da própria tag. O `qwen3.5` mostra o
  tamanho como faixa porque cada tag tem duas variantes: `llamacpp` (GGUF Q4_K_M ou Q8_0 + projetor de visão
  BF16) e `mlx` (Apple). A estimativa usa o maior valor.
- **`config.json` oficiais da Qwen no Hugging Face**: `num_hidden_layers`, `num_key_value_heads`, `head_dim`
  (ou `hidden_size / num_attention_heads`) e, no `qwen3.5`, `layer_types` (1 camada de atenção completa a
  cada 4; as demais são lineares, com estado fixo).
- **Parâmetros ativos** dos modelos de mistura de especialistas (3,3 B de 30,5 B): cartões oficiais.

### 2. Estimativa de memória

```
pesos      = downloadBytes (limite superior do tamanho da biblioteca; inclui o projetor de visão)
cache KV   = 2 × camadas de atenção × cabeças KV × dimensão da cabeça × 2 bytes (f16) × contexto
estado     = stateBytes (camadas lineares do Qwen3.5; ~21 MB a ~53 MB, independente do contexto)
sobrecarga = 512 MiB (buffers de cálculo e o runtime da GPU, que o /api/ps não conta)
```

O contexto da estimativa é **8.192 tokens**: uma conversa com algum histórico. A geração de documentação já
reserva 8.192 tokens só para a resposta.

**Calibração** (2026-10-25, Ollama 0.32.11, RTX 5070 de 12 GB), com o `size` do `/api/ps`:

| Modelo | Contexto | Medido | Fórmula |
|---|---:|---:|---:|
| `qwen2.5:14b` (8.988.124.069 bytes) | 4.096 | 9,47 GB | 10,3 GB |
| | 8.192 | 10,77 GB (já com offload parcial: 10,09 GB na GPU) | 11,1 GB |
| | 16.384 | 12,42 GB | 12,7 GB |
| `llama3.2` 3B (2.019.393.189 bytes) | 4.096 | 2,55 GB | 3,0 GB |
| | 16.384 | 4,06 GB | 4,4 GB |

A fórmula fica de 0,3 GB a 0,8 GB acima da medição: conservadora, como deve ser. Um teste reprova a fórmula se
ela ficar abaixo dessas medições. Um cache KV quantizado pelo usuário (`OLLAMA_KV_CACHE_TYPE`) só diminui o
número real.

### 3. Memória disponível

- **RAM:** 90% da memória disponível agora (`MemAvailable` no Linux). Sem esse dado, metade do total.
- **GPU dedicada:** 90% da memória livre da melhor placa NVIDIA ou AMD **confirmada pelo driver** (o OSHI
  lê pela NVML ou pelos arquivos do `amdgpu`; o `nvidia-smi` é o complemento). Placa sem confirmação, Intel e
  outras ficam de fora, e a estimativa considera só a CPU, com o motivo na tela.
- **Apple Silicon:** 90% do menor valor entre dois terços da RAM e a RAM disponível agora. **Premissa não
  verificada:** a Apple não documenta a fração da memória unificada que a GPU pode usar
  (`recommendedMaxWorkingSetSize`); relatos de usuários citam cerca de 75%, e dois terços é a escolha cautelosa.
  Sem Mac nesta fase, nada disso foi medido.

### 4. Classes

| Classe | Regra | Na tela |
|---|---|---|
| Recomendado | Cabe inteiro no orçamento da GPU | "Cabe inteiro na placa de vídeo… Deve responder rápido." |
| Funciona com offload | A GPU fica com pelo menos 30% e o resto cabe na RAM (no Apple Silicon: cabe na RAM) | "…parte roda na CPU, mais devagar." |
| Só CPU | Sem GPU utilizável, ou ela ficaria com menos de 30%, e cabe na RAM | "…Funciona, mas devagar." (e "bem lentas" acima de 4,5 B de parâmetros ativos) |
| Não recomendado | Não cabe | "…Pode deixar tudo lento ou nem abrir. Fechar outros programas libera memória." |

O usuário pode escolher qualquer modelo; para um "Não recomendado", a confirmação do download exige marcar que
entendeu o aviso.

### 5. Sugestões

Uma para uso geral e outra para código: a melhor classe disponível e, dentro dela, o modelo com mais parâmetros
(indicador de qualidade). Abaixo da GPU, só entre os rápidos o bastante para aquele hardware: até 15 B de
parâmetros ativos com offload e até 4,5 B só na CPU; sem nenhum, o menor. Empates mantêm a ordem do catálogo.

Resultado para os perfis do pedido (testes do `ModelRecommenderTest`):

| Perfil | Geral | Código |
|---|---|---|
| Notebook só com CPU, 8 GB | `qwen3.5:4b` (só CPU) | `qwen2.5-coder:3b` (só CPU) |
| GPU de 6 GB | `qwen3.5:4b` | `qwen2.5-coder:3b` |
| GPU de 12 GB | `qwen3.5:9b` | `qwen2.5-coder:7b` |
| Apple Silicon, 16 GB | `qwen3.5:9b` | `qwen2.5-coder:7b` |
| Máquina com 4 GB | `qwen3:0.6b` (só CPU) | `qwen2.5-coder:1.5b` (só CPU) |

Na máquina real desta fase (RTX 5070 de 12 GB, 31 GB de RAM), o app sugeriu os mesmos da linha "GPU de 12 GB".

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Classificar pela VRAM nominal | Ignora o cache KV e confia num número que, sem driver, pode ser só a janela PCI. |
| Ler a arquitetura do `/api/show` em vez do catálogo | Só existe depois do download; a recomendação é para escolher o que baixar. O `/api/show` continua servindo para conferir o modelo instalado. |
| Medir carregando cada modelo | Exigiria baixar dezenas de GB. |
| Velocidade em tokens por segundo | Não foi medida por modelo e hardware; prometer números seria desonesto. As classes dizem "rápido", "mais devagar" e "devagar". |

## Consequências

- O catálogo precisa de manutenção: novas famílias e mudanças de tag exigem nova verificação, com data. A
  biblioteca do Ollama atualiza tags sem aviso (o `qwen3.5` mudou de digest durante a fase).
- O orçamento usa a memória livre **agora**: a mesma máquina pode ver classes diferentes com outros programas
  abertos ou com um modelo já carregado na GPU (visto no teste ponta a ponta: 9,5 GB livres antes do download,
  8,9 GB depois, com o `qwen3:0.6b` na memória).
- Máquinas com GPU Intel ou com placa sem driver oficial ficam como "só CPU", mesmo que o Ollama consiga usar a
  GPU. É o erro do lado seguro.
- A comparação da estimativa com o `/api/ps` de cada modelo carregado (R9) fica para quando o chat mantiver
  modelos carregados (Fase 4).
