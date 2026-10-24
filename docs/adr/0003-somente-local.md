# ADR 0003: Somente local (Ollama em loopback, sem provedor remoto)

| | |
|---|---|
| Estado | **Aceita** (decisão 4.3 do pedido de realinhamento). O bloqueio de modelos de nuvem depende de verificação na Fase 3. |
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
3. **Contexto explícito (Fase 1).** Toda chamada envia `options.num_ctx`, dimensionado pela estimativa de
   tokens do prompt mais a folga de saída e limitado ao máximo do modelo. Se o prompt não couber, a
   operação **falha fechada**, com mensagem que orienta a reduzir a entrada; o prompt nunca é cortado em
   silêncio.
4. **Modelos de nuvem bloqueados (Fase 3).** O DocGen só baixa tags do catálogo Qwen versionado. Antes de
   usar um modelo instalado, ele confirma que é local. **O método de detecção vai ser verificado na Fase 3**
   contra a documentação e o Ollama real; se não houver método confiável, a fase para e eu apresento o
   trade-off antes de seguir. O onboarding explica, em linguagem simples e com link para a documentação
   oficial, como ligar o modo só local do Ollama.
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
| `LocalOnlyArchitectureTest` (Java) | 1 | Literal `http://` ou `https://` no código e nos recursos de produção de qualquer módulo que não seja loopback (`127.0.0.1`), salvo uma lista revisada de referências que nunca são buscadas, cada uma com o motivo. Também falha com classes proibidas (`GroqProvider`, `RemoteGate`, `RemoteConsentService`), com implementação de `LLMProvider` diferente da do Ollama e com uso de `HttpClient` ou `URLConnection` fora do cliente Ollama. |
| `security.test.mjs` (frontend) | 1 | URL absoluta externa fora da lista de links permitidos, `fetch` para origem absoluta e carregamento de recurso externo (os *sinks* HTML já são checados hoje). |
| Teste do endpoint do Ollama | 1 | Host diferente de loopback aceito, porta fora da faixa aceita, uso de proxy ou redirecionamento seguido. |
| Teste de contexto | 1 | Prompt maior que o contexto enviado sem falha ou sem `num_ctx`. |
| Testes do catálogo e do pull | 3 | Pull de tag fora do catálogo; uso de modelo identificado como de nuvem. |

A varredura de texto não prova a ausência de tráfego em tempo de execução (uma URL pode ser montada por
partes). Ela é a rede de segurança contra regressões óbvias, somada à revisão de código e às restrições
estruturais acima: um único cliente HTTP, endpoint montado de host constante e sem proxy.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Manter o Groq atrás do consentimento existente | Bem protegido, mas contradiz a promessa e o critério de rejeição 1. |
| Host do Ollama configurável com allowlist | Abre espaço para apontar para outra máquina (um Ollama "local" na rede). Sem configuração de host não há o que errar. |
| Confiar no `OLLAMA_NO_CLOUD` do usuário | Depende de terminal ou de arquivo de configuração e não é garantido. Vale como recomendação, não como controle. |
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
