# ADR 0003: Somente local (Ollama em loopback, sem provedor remoto)

| | |
|---|---|
| Estado | **Aceita e implementada** (decisão 4.3 do pedido de realinhamento, com as camadas contra modelos de nuvem e as regras de contexto definidas na aprovação da Fase 0; remoção na Fase 1, camadas contra modelos de nuvem na Fase 3) |
| Data | 2026-10-24 |
| Fase | Proposta na Fase 0; implementação nas Fases 1 (remoção) e 3 (cliente Ollama completo) |
| Relacionadas | [ADR 0001](0001-shell-desktop.md), [ADR 0002](0002-autenticacao-por-token-de-lancamento.md), [roadmap](../desktop/ROADMAP.md) |

## Contexto

A promessa do produto é "nada sai do seu computador". Hoje o código tem uma fronteira remota completa:
`GroqProvider` (HTTPS para `api.groq.com`, que é o provedor **padrão** da CLI), `RemoteGate`,
`RemoteConsentService`, `docgen.remote.enabled`, `--allow-remote`/`--yes` na CLI e o diálogo de consentimento
na web. Ela é bem protegida (consentimento de uso único amarrado ao digest do prompt), mas a promessa exige
que esse caminho **não exista**.

Dois fatos verificados na Fase 0 mudam o desenho:

- **O Ollama tem modelos de nuvem.** A documentação oficial diz que a instância local pode "falar com
  ollama.com". O modo só local se liga com `OLLAMA_NO_CLOUD=1` ou com `disable_ollama_cloud` em
  `~/.ollama/server.json` (docs.ollama.com/faq). A documentação **não** diz como um cliente local reconhece um
  modelo de nuvem. Sem cuidado, o DocGen tiraria a inferência do computador *através* do Ollama.
- **O contexto padrão do Ollama trunca prompts em silêncio.** Abaixo de 24 GiB de VRAM, o padrão é 4k tokens
  (docs.ollama.com/context-length). Medido na Fase 0: um prompt de 109.951 caracteres foi avaliado com
  2.050 tokens, e a regra da primeira linha foi ignorada. Não é tráfego de rede, mas é uma fronteira de
  confiança: as regras contra *prompt injection* ficam no início do prompt.

## Decisão

1. **Um único provedor: Ollama.** Saem `GroqProvider`, `RemoteGate`, `RemoteConsentService`, `PromptDigest`,
   `docgen.remote.*`, `--allow-remote` e `--yes`, `consent.js`, a prévia de transmissão, os testes e os textos
   sobre o Groq. `LLMProvider.isRemote()` some. `ProviderSelection` e `LLMProviderResolver` perdem
   `allowRemote`.
2. **Endpoint fixo em loopback.**
   - O host é o literal `127.0.0.1`, sem resolução de nome: `localhost` poderia resolver para `::1` ou ser
     redirecionado pelo arquivo `hosts`.
   - A porta é configurável (1–65535, padrão `11434`): na CLI por opção e no app pelas configurações locais.
   - **Não existe configuração de host.** O DocGen não lê `OLLAMA_HOST`, e qualquer valor de host que não
     seja loopback é recusado.
   - O cliente HTTP usa `DirectProxySelector` (nunca proxy do sistema), não segue redirecionamentos
     (`Redirect.NEVER`, explícito), tem *connect timeout* curto, prazo fim a fim interrompível
     (`HttpExchange`) e limites de bytes, linhas e fragmentos (os de hoje, estendidos ao chat e ao pull).
3. **Contexto explícito (Fase 1, em commit próprio).**
   - Toda chamada envia `options.num_ctx`, dimensionado pelo tamanho **estimado** do prompt mais a reserva de
     saída e com teto no contexto máximo do modelo, informado pelo `/api/show`.
   - Se o prompt não couber, a operação **falha fechada**, com erro claro que orienta a reduzir a entrada ou a
     escolher um modelo com contexto maior. O prompt nunca é cortado em silêncio.
   - Um teste de regressão reproduz o truncamento medido na Fase 0: o servidor falso imita o Ollama, que avalia
     só 2.050 tokens de um prompt de 109.951 caracteres quando o contexto não é informado.
4. **Modelos de nuvem: defesa em camadas (Fase 3).**
   1. **Lista de permitidos pelo catálogo**, tanto para baixar quanto para escolher um modelo no app.
   2. **Rejeição** de modelos instalados cuja tag ou cujos metadados indiquem execução remota.
   3. **Verificação**, na documentação e empiricamente, do que o `/api/show` (e o `/api/tags`) devolvem para
      modelos de nuvem, antes de escrever a regra da camada 2. Se não houver sinal confiável, a fase para e eu
      apresento o trade-off.
   4. **Nenhuma dependência da configuração local do Ollama** do usuário (`OLLAMA_NO_CLOUD` ou
      `disable_ollama_cloud`): as camadas 1 e 2 valem mesmo com a nuvem ligada.
   5. **Onboarding**: explica e recomenda o modo só local do Ollama, em linguagem simples, citando a fonte
      oficial (docs.ollama.com/faq).
5. **Download de modelo é a única exceção, e é explícita.** Quem baixa é o próprio Ollama (`/api/pull`), e só
   depois de um clique numa tela que diz que haverá download, de onde (o registro oficial do Ollama; o host
   exato será verificado na Fase 3) e com qual tamanho aproximado. Depois de baixado, tudo funciona offline.
6. **Frontend sem nada externo.** Fontes, ícones e scripts vão empacotados, e a CSP continua com
   `default-src 'none'`, `connect-src 'self'`, `font-src 'self'` e `img-src 'self' data:`. Links externos
   (por exemplo, a página oficial de download do Ollama) só existem como navegação iniciada pelo usuário,
   com `rel="noopener noreferrer"`. O app nunca busca nada neles.
7. **Sem telemetria, analytics, relatório de falhas ou verificação de atualização.** Atualizar o app é uma
   ação do usuário, pelo site.

## Como isso é garantido por teste

| Teste | Fase | O que falha o build |
|---|---|---|
| `LocalOnlyArchitectureTest` (Java) | 1 | Literal `http://` ou `https://` no código e nos recursos de produção de qualquer módulo que não seja loopback (`127.0.0.1`), salvo uma lista revisada de referências que nunca são buscadas, cada uma com o motivo. Também falha com classes proibidas (`GroqProvider`, `RemoteGate`, `RemoteConsentService`), com implementação de `LLMProvider` diferente da do Ollama e com uso de `HttpClient` ou `URLConnection` fora do cliente Ollama. Desde a Fase 2, a única outra exceção é o `ReopenClient` do launcher (ADR 0001), que só fala com o próprio app em `127.0.0.1`; o teste confere que ele usa o host constante, sem proxy e sem seguir redirecionamentos. |
| `security.test.mjs` (frontend) | 1 | URL absoluta externa fora da lista de links permitidos, `fetch` para origem absoluta e carregamento de recurso externo (os *sinks* HTML já são checados hoje). |
| Teste do endpoint do Ollama | 1 | Host diferente de loopback aceito, porta fora da faixa aceita, uso de proxy ou redirecionamento seguido. |
| Teste de contexto | 1 | Prompt maior que o contexto enviado sem falha ou sem `num_ctx`. |
| Teste de regressão do truncamento | 1 | Servidor falso que imita o Ollama truncando um prompt de 109.951 caracteres em 2.050 tokens quando falta `num_ctx`; o provedor precisa mandar `num_ctx` suficiente ou falhar fechado. |
| Testes do catálogo e do pull | 3 | Pull ou seleção de tag fora do catálogo; uso de modelo instalado cuja tag ou cujos metadados indicam execução remota (com fixtures das respostas reais verificadas). |

A varredura de texto não prova a ausência de tráfego em tempo de execução (uma URL pode ser montada por
partes). Ela é a rede de segurança contra regressões óbvias, somada à revisão de código e às restrições
estruturais acima: um único cliente HTTP, endpoint montado de host constante e sem proxy.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Manter o Groq atrás do consentimento existente | Bem protegido, mas contradiz a promessa e o critério de rejeição 1. |
| Host do Ollama configurável com allowlist | Abre espaço para apontar para outra máquina (um Ollama "local" na rede). Sem configuração de host não há o que errar. |
| Confiar no `OLLAMA_NO_CLOUD` do usuário | Depende de terminal ou de arquivo de configuração e não é garantido. Vale como recomendação (camada 5), não como controle. |
| Cortar o prompt para caber no contexto | Ou corta as regras (o que o Ollama faz hoje), ou corta dados sem avisar. Falhar fechado, com aviso e com corte explícito de histórico no chat, é honesto. |

## Consequências

- **Quebra de compatibilidade** para quem usa `--provider groq`, `--allow-remote` ou `--yes`: versão
  `3.0.0-SNAPSHOT`, registrada no CHANGELOG. `--provider ollama` continua aceito.
- Não há "fallback na nuvem" para máquinas fracas: a qualidade e a velocidade dependem do hardware, e o
  recomendador (Fase 3) precisa ser honesto sobre isso.
- O comportamento do próprio Ollama (recursos de nuvem, atualização do app Ollama) fica fora do controle do
  DocGen. A UI e a documentação dizem isso com clareza, em vez de prometer o que não depende do DocGen.
- O padrão `gsk_` do `SecretRedactor` **continua**: ele redige segredos que aparecem nos dados do usuário e
  não é integração com o Groq. Removê-lo enfraqueceria a redação.

## Implementação da Fase 3 (2026-10-25)

Estado: **Implementado**, com testes. Camadas contra modelos que rodam fora do computador:

| Camada | Como ficou | Evidência |
|---|---|---|
| 1. Lista de permitidos | O catálogo versionado ([ADR 0004](0004-catalogo-e-recomendacao-de-modelos.md)) é a única fonte de tags para download, teste e modelo padrão. Tags com host (`registro/modelo:tag`) ou `cloud` não entram nele. | `ModelCatalogTest`, `ModelApiTest.onlyCatalogTagsAreEverDownloaded`, `SettingsApiTest` |
| 2. Rejeição por tag ou metadados | `RemoteModels`: `remote_host` ou `remote_model` preenchidos no `/api/tags`, no `/api/show` ou num pedaço do `/api/chat`, ou tag `cloud`/`*-cloud`. O `OllamaProvider` (CLI e Engenharia) e o `OllamaClient.chat` consultam o `/api/show` antes de enviar qualquer prompt. | `OllamaClientTest`, `OllamaChatTest`, `OllamaProviderSafetyTest` |
| 3. Verificação | Código do Ollama (`api/types.go`: `RemoteModel`/`RemoteHost` em `ListModelResponse`, `ShowResponse`, `ChatResponse`) e teste empírico no Ollama 0.32.11: um modelo criado com `remote_host` (manifesto local, sem rede) voltou com os dois campos, tamanho de 191 bytes e `details.format` vazio. As respostas reais viraram fixtures (`docgen-core/src/test/resources/ollama`). | `SOURCES.md` das fixtures |
| 4. Independência da configuração do Ollama | Nada lê `OLLAMA_NO_CLOUD` nem `server.json`. | — |
| 5. Onboarding | A etapa do Ollama recomenda o modo só local e leva à documentação oficial. | `views/onboarding.js` |

Verificado também no app real, com o JAR e o Ollama 0.32.11: um modelo remoto criado com o nome `qwen3:1.7b` (uma
tag do catálogo, então a camada 1 não o barra) foi recusado pela camada 2 no teste de inferência e como modelo
padrão (`422 remote_model_refused`), sem nenhum prompt enviado.

**Download (item 5 da decisão).** O host foi verificado no código do Ollama (`types/model/name.go`: host padrão
`registry.ollama.ai`, namespace `library`); a janela de confirmação diz isso. O cancelamento foi verificado no
0.32.11: fechar a conexão do `/api/pull` parou o download (265,4 MB no cancelamento, 267,9 MB oito segundos depois)
e o pull seguinte retomou do ponto em que parou, como diz a `api.md`. O Ollama manda erros de pull **no meio do
stream, com HTTP 200**; o cliente os trata.

**Links externos (item 6).** Duas páginas oficiais do Ollama (download e modo só local) ficam só em
`docgen-web/.../js/links.js`, abertas pelo usuário numa aba nova com `rel="noopener noreferrer"`. Os testes Java e
JS reprovam qualquer outro endereço externo e o uso dessas constantes para buscar algo.

**Pendente:** o campo de modelo dos projetos da Engenharia ainda aceita qualquer nome (a camada 2 cobre); ele passa
à lista do catálogo na Fase 6. A CLI continua aceitando qualquer `--model`, também coberta pela camada 2.
