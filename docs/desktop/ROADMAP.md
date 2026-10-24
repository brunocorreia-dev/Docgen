# DocGen Desktop: roadmap de realinhamento

> **DocGen: seu assistente local e gratuito. Sua IA. Seu computador. Suas regras.**

| | |
|---|---|
| Fase | 0: auditoria e plano (sem código de produção) |
| Data | 2026-10-24 |
| Branch | `feat/desktop-fase0-auditoria`, criada a partir de `main` (`9d65fc1`) |
| Estado deste documento | Proposta, aguardando aprovação |
| Decisões relacionadas | [ADR 0001](../adr/0001-shell-desktop.md), [ADR 0002](../adr/0002-autenticacao-por-token-de-lancamento.md), [ADR 0003](../adr/0003-somente-local.md) |

Legenda de estado usada em todo o documento: **Implementado** (existe em `main` e tem teste), **Em
desenvolvimento** (existe numa branch, fora de `main`) e **Planejado** (ainda não existe).

## 1. Objetivo

Transformar o DocGen num aplicativo desktop para Linux, Windows e macOS que roda modelos **Qwen** via
**Ollama** por uma interface gráfica, sem terminal, com o **chat como tela principal**. Os recursos atuais de
planejamento arquitetural e documentação viram ferramentas secundárias ("Engenharia").

Hoje o produto se desviou disso: Groq é o provedor padrão da CLI, o acesso web exige
`DOCGEN_API_USERNAME`/`DOCGEN_API_PASSWORD`, a tela inicial é um dashboard de projetos e a Vercel publica o
frontend do app (que não funciona sem o backend).

Princípios que servem de critério de rejeição: nada sai do computador (só o Ollama em loopback; a única
exceção é o download de modelo feito pelo próprio Ollama após clique explícito), sem telemetria nem CDN, sem
banco em nuvem, sem terminal para o usuário final, nenhuma regressão de segurança, só dependências livres e
compatíveis, e honestidade técnica (nada prometido sem medir).

## 2. Linha de base (medida antes de qualquer mudança)

Ambiente: Linux x86_64 (Arch, kernel 7.1.5), 12 CPUs lógicas, 31 GiB de RAM. A CI usa Temurin 21; aqui não
há JDK 21, então o build usou **JetBrains Runtime 25.0.4** com `--release 21`. Maven 3.9.16, Node.js 24.16.0.
Ollama 0.32.11 respondendo em `127.0.0.1:11434`.

Comandos: `mvn -B clean verify` (o `clean` garante medição sem cache de build) e
`node --test docgen-web/src/test/js/*.test.mjs` (o mesmo glob da CI).

### `main` (`9d65fc1`), a referência das próximas fases

| Módulo | Testes | Falhas | Erros | Ignorados | Tempo do módulo |
|---|---:|---:|---:|---:|---:|
| docgen-core | 171 | 0 | 0 | 0 | 5,3 s |
| docgen-cli | 13 | 0 | 0 | 0 | 2,7 s |
| docgen-web (empacotamento) | — | — | — | — | 0,04 s |
| docgen-api | 48 | 0 | 0 | 0 | 12,9 s |
| **Total Java** | **232** | **0** | **0** | **0** | **21,2 s** (`BUILD SUCCESS`) |
| Frontend (`node --test`) | **44** | 0 | — | 0 | 0,10 s |

Por classe: core: `RepoScannerTest` 40, `SecretRedactorTest` 39 (inclui parametrizados), `OutputWriterTest`
18, `ArchitecturePlanParserTest` 10, `DocumentationServiceTest` 10, `ProviderResponseValidationTest` 8,
`PromptBuilderTest` 8, `PromptPreviewWriterTest` 8, `DocumentationValidatorTest` 7,
`ArchitecturePlanningServiceTest` 6, `MermaidDiagramTest` 4, `ProjectDocumentationServiceTest` 4,
`UntrustedTextTest` 4, `ProjectPromptBuilderTest` 3, `ProviderJsonModeTest` 2. CLI: `DocGenCLITest` 10,
`SafeTextTest` 3. API: `AuthAndSecurityTest` 12, `WorkflowApiTest` 11, `DocGenApiApplicationTest` 10,
`AttachmentApiTest` 6, `ProjectApiTest` 5, `AuthorizedRepositoryRegistryTest` 3, `JobTimeoutTest` 1.

Avisos do build (já existiam): o `maven-shade-plugin` relata recursos sobrepostos (`META-INF/LICENSE`,
`NOTICE`, `MANIFEST.MF`, `module-info`) entre Jackson e Picocli no `docgen.jar`.

### `feat/docgen-desktop-launcher` (`08a9720`), só para comparação

| Módulo | Testes |
|---|---:|
| docgen-core / docgen-cli | 171 / 13 |
| docgen-desktop-protocol | 10 |
| docgen-api | 79 |
| docgen-launcher | 105 |
| **Total Java** | **378**, 0 falhas, `BUILD SUCCESS` em 1 min 08 s |
| Frontend | 51, 0 falhas |

## 3. Achados da auditoria

| # | Achado | Evidência | Consequência |
|---|---|---|---|
| A1 | **O Ollama trunca prompts longos em silêncio.** O `OllamaProvider` não envia `options.num_ctx`. Pela documentação oficial, abaixo de 24 GiB de VRAM o contexto padrão é 4k tokens. | Medido aqui: um prompt de 109.951 caracteres (cerca de 30 mil tokens, estimativa) com uma regra na primeira linha voltou com `prompt_eval_count: 2050`, e o modelo **não seguiu a regra** do início. | As regras de segurança ficam no início dos prompts (`PromptBuilder`, `ProjectPromptBuilder`) e são justamente o que se perde. Os prompts de repositório (até 120 mil caracteres) e de planejamento (até 64 mil) excedem 4k tokens. É defeito de segurança e de qualidade **já presente em `main`**. |
| A2 | **Já existe um launcher desktop numa branch publicada**, com desenho diferente das decisões 4.1/4.2: janela Swing num processo separado, backend como processo filho, protocolo HMAC por stdin/stdout e pareamento do navegador com código confirmado na janela. | `feat/docgen-desktop-launcher`: 13 commits à frente de `main`, também em `origin`; 97 arquivos, +5.445 linhas de produção (4.445 em `docgen-launcher` e `docgen-desktop-protocol`, 1.000 em API e web) e +3.533 de testes. | Caminhos de reaproveitamento e de descarte estão na seção 4.6. É preciso decidir antes da Fase 2 (pergunta P1). |
| A3 | O `OllamaProvider` depende de `GroqProvider.boundedUtf8BodySubscriber`. | `OllamaProvider.java:101` | Antes de apagar o Groq, o leitor limitado de corpo precisa ir para uma classe própria. |
| A4 | O banco pode ter linhas com `provider = 'groq'` (projetos, planos, jobs). | `V1__initial_schema.sql`, `ProjectDtos.PROVIDER = "groq\|ollama"` | Projetos antigos quebrariam o `ProviderSelection` depois da remoção. É preciso uma migração nova (seção 7). |
| A5 | O app não sobe sem `DOCGEN_API_USERNAME`/`DOCGEN_API_PASSWORD` (`@NotBlank`). Toda linha do banco tem `owner` = usuário do login. | `DocGenApiProperties.Api`, `SecurityConfig.userDetailsService` | Sem login, o dono passa a ser fixo (proposta: `local`), com migração dos donos existentes. O escopo por `owner` continua como camada de defesa. |
| A6 | O diretório de dados é XDG em **todos** os SOs (no Windows, cai em `~\.local\share\docgen`). | `StorageConfig.resolveDataDirectory` | Precisa virar caminho por SO (Fase 2). No Linux, o caminho atual se mantém e os dados existentes continuam válidos. |
| A7 | A Vercel publica o **frontend do app** (`docgen-web/src/main/resources/static`), que não funciona sem o backend. | `vercel.json` | Pode confundir quem chega ao site. A correção definitiva fica na Fase 8; a pergunta P8 propõe neutralizar antes. |
| A8 | Na CLI, o provedor padrão é `groq` e o modelo padrão do Ollama é `llama3.2` (não é Qwen). | `DocGenCLI`, `LLMProviderFactory` | Na Fase 1 o padrão vira Ollama e o modelo padrão vira uma tag Qwen verificada (pergunta P6). |
| A9 | O `SecretRedactor` tem um padrão para chaves `gsk_…` (formato do Groq). | `SecretRedactor.java:21` | Isso é **defesa** (redige segredos nos dados do usuário), não integração. Proposta: manter o padrão, com comentário neutro. Remover enfraqueceria a redação (princípio 5). |
| A10 | O Ollama tem **modelos de nuvem**: a instância local pode falar com ollama.com. A FAQ oficial permite desligar com `OLLAMA_NO_CLOUD=1` ou `disable_ollama_cloud` em `~/.ollama/server.json`. A documentação não diz como um cliente local identifica um modelo de nuvem. | docs.ollama.com/cloud, docs.ollama.com/faq | Um modelo de nuvem escolhido no DocGen tiraria a inferência do computador **pelo próprio Ollama**. O DocGen precisa bloquear isso (ADR 0003, Fase 3). |
| A11 | O Ollama 0.32.11 local devolve, em `/api/tags`, campos que a `api.md` não documenta (`details.context_length`, `capabilities`). A própria `api.md` avisa que a documentação está migrando para docs.ollama.com. | `curl /api/tags` local; `docs/api.md` | O cliente da Fase 3 deve tratar campos opcionais e ser testado contra a versão real, não só contra a documentação. |
| A12 | Gerar README/ARCHITECTURE **a partir de um repositório** só existe na CLI. A API só *analisa* repositórios mapeados pelo operador em `docgen.repositories`, que é configuração de arquivo ou ambiente. | `RepositoryAnalysisService`, `ApiController` | No app (sem terminal), o "gere um README deste projeto" da Fase 6 precisa de um jeito de autorizar uma pasta pela UI (pergunta P7). |
| A13 | O backend só loga no console. Num app aberto pelo menu, esses logs se perdem. | `application.yml` (sem arquivo de log) | A Fase 2 precisa de log local em arquivo, rotacionado e sem conteúdo de conversa. |
| A14 | Fontes: o CSS cita "Inter" e "JetBrains Mono" só por nome (cai na fonte do sistema). Nada externo é carregado. | `app.css` | Fontes empacotadas com licença OFL na Fase 4. |
| A15 | Mensagens de erro do servidor são fixas e em inglês. A UI traduz por `code` (`messages.js`). | `ApiExceptions`, `messages.js` | Manter o padrão: códigos estáveis no servidor e texto em PT-BR na UI. |

## 4. Inventário: manter, adaptar ou remover

Coluna "Fase": quando a mudança acontece. "Manter" significa sem mudança de comportamento (a classe pode
mudar de pacote ou receber ajustes triviais).

### 4.1 `docgen-core`

| Classe | Ação | Fase | Motivo |
|---|---|---|---|
| `RepoScanner`, `DocgenIgnore`, `RepoContext` | Manter | — | Núcleo de segurança do pipeline de repositórios (`SecureDirectoryStream`, symlinks, limites). |
| `SecretRedactor` | Manter | — | Redação reutilizada no chat e nos anexos. O padrão `gsk_` fica (A9). |
| `UntrustedText`, `SafeText` | Manter | — | Normalização de texto não confiável, reutilizada no chat e nos anexos. |
| `PromptBuilder` | Manter | — | Prompts de repositório com delimitadores e neutralização. |
| `ProjectPromptBuilder` | Manter (padrão reaproveitado) | 5 | Os delimitadores de dado não confiável viram referência para os anexos do chat. |
| `DocumentationValidator`, `UnsafeOutputException` | Manter | — | Markdown passivo antes de publicar. |
| `SecurePairWriter`, `OutputWriter`, `PromptPreviewWriter` | Manter | — | Escrita transacional da CLI. |
| `ArchitecturePlan`, `ArchitecturePlanJson`, `ArchitecturePlanParser`, `MermaidDiagram` | Manter | 6 | Engenharia (planejamento). |
| `ProjectBrief`, `SupportingDocument`, `DocumentationArtifacts`, `DocumentationResult` | Manter | — | Tipos de domínio. `SupportingDocument` serve aos anexos do chat. |
| `HttpExchange`, `DirectProxySelector` | Manter | — | Prazo fim a fim interrompível e Ollama nunca via proxy. |
| `DocumentationService` | Adaptar | 1 | Remover o ramo remoto e os tipos `RemoteConsent`, `RemoteTransmission` e `RemoteConsentDeniedException`. |
| `DocumentationRequest` | Adaptar | 1 | Remover `allowRemote`. O provedor deixa de ser escolha. |
| `ArchitecturePlanningService`, `ProjectDocumentationService` | Adaptar | 1 | Remover `RemoteGate` e o callback de consentimento; remover `Preparation.digest`. |
| `LLMProvider` | Adaptar | 1 | Remover `isRemote()`. `describeDestination()` passa a só descrever o endpoint local. |
| `LLMProviderFactory`, `LLMProviderResolver`, `ProviderSelection` | Adaptar | 1 | Só Ollama, sem `allowRemote`, porta configurável e validada, nome de modelo com o mesmo padrão restrito. |
| `OllamaProvider` | Adaptar | 1 → 3 | F1: loopback fixo, porta configurável, `num_ctx` explícito e falha fechada quando o prompt não cabe (A1). F3: evolui para um cliente completo (`version`, `tags`, `show`, `ps`, `pull`, `delete`, `chat` com streaming e cancelamento). |
| `GroqProvider` | **Remover** | 1 | Provedor remoto. Antes, extrair o leitor limitado de corpo (A3). |
| `RemoteGate` | **Remover** | 1 | Fronteira remota. |
| `PromptDigest` | **Remover** | 1 | Só servia para amarrar o consentimento remoto ao prompt. |
| Novo: leitor limitado de corpo HTTP (nome a definir) | Planejado | 1 | Recebe o código que hoje está em `GroqProvider`. |
| Novo: cliente Ollama e tipos de resposta | Planejado | 3 | Endpoints da seção 5. |
| Novo: montador de prompt do chat | Planejado | 4–5 | Histórico, *system prompt* e anexos dentro de delimitadores de dado não confiável. |

### 4.2 `docgen-cli`

| Arquivo | Ação | Fase | Motivo |
|---|---|---|---|
| `DocGenCLI` | Adaptar | 1 | Remover `--allow-remote`, `--yes` e a confirmação remota. Padrão Ollama. `--provider` aceita só `ollama`, para scripts antigos continuarem válidos. Nova opção de porta do Ollama. Versão 3.0.0-SNAPSHOT. |
| `DocGenCLITest` | Adaptar | 1 | Sai o que só testa a fronteira remota (`remoteProviderStillRequiresAllowRemote`, `yesNeverReplacesTheAllowRemoteBoundary`, `nonInteractiveRemoteRunRequiresExplicitYes` e o caso que usa `--allow-remote`). Entra a geração de README/ARCHITECTURE com provedor Ollama falso. |
| `SafeTextTest` | Manter | — | — |
| `docgen` (wrapper) | Manter | — | Uso de desenvolvedor. |

### 4.3 `docgen-api`

| Classe ou recurso | Ação | Fase | Motivo |
|---|---|---|---|
| `HostHeaderFilter` | Adaptar | 2 | Exigir host **e porta** (`127.0.0.1:<porta>` ou `localhost:<porta>`). `::1` sai, porque o servidor nem escuta em IPv6. |
| `RequestPolicyFilter` | Adaptar | 2, 5 | F2: validar `Origin` e `Sec-Fetch-Site` em métodos que alteram estado, porque o cookie passa a ser credencial ambiente. F5: tipos `text/markdown` e `text/plain` para anexos do chat. Os limites de tamanho continuam. |
| `SecurityConfig` | Adaptar | 2 | Sai Basic, `UserDetailsService`, `PasswordEncoder` e `AuthenticationManager`. Entra o filtro de cookie de sessão. `/launch` fica público. A CSP continua igual. |
| `AuthTokenService` | Adaptar | 2 | Vira o repositório de sessões (só o hash, em memória) e de tokens de lançamento de uso único. |
| `BearerTokenAuthenticationFilter` | Substituir | 2 | Por um filtro de cookie `HttpOnly` (ADR 0002). |
| `AuthController` | **Remover** | 2 | Login por usuário e senha. Substituído pela troca do token de lançamento. |
| `LoginThrottle`, `ThrottledAuthenticationManager` | **Remover** | 2 | Sem senha, não há força bruta de login. O token de lançamento tem 256 bits e é de uso único. |
| `CurrentUser` | Adaptar | 2 | Dono fixo da instalação (proposta: `local`). |
| `DocGenApiApplication` | Adaptar | 2 | A classe principal passa a ser o `DesktopLauncher` (instância única antes do Spring, porta livre, navegador). |
| `DocGenApiProperties` | Adaptar | 1, 2 | F1: sai `Remote`. F2: sai `Api.username`/`password`/`additionalUsers`; os hosts permitidos passam a ser derivados da porta real; entram `ollama.port` e `desktop.*`. |
| `StorageConfig` | Adaptar | 2 | Diretório de dados por SO (ADR 0001), permissões privadas e base para "Apagar todos os meus dados". |
| `StrictJsonConfig`, `Ids`, `JsonColumns` | Manter | — | — |
| `ApiExceptionHandler`, `ApiExceptions` | Adaptar | 1, 2 | Saem os códigos `consent_*`, `remote_disabled`, `invalid_credentials` e `too_many_attempts`. Entram códigos do Ollama e do chat. |
| `ProviderConfig` | Adaptar | 1 | Só Ollama; sai `groqKeyConfigured`. |
| `RemoteConsentService` | **Remover** | 1 | Consentimento remoto. |
| `AiWorkflowService` | Adaptar | 1, 6 | F1: saem a prévia de transmissão e o consumo de consentimento. F6: integra com a seção Engenharia e com o chat. |
| `WorkflowController`, `WorkflowDtos` | Adaptar | 1 | Sai `POST /transmission-previews`; sai `consentToken` dos pedidos. |
| `ApiController`, `ApiDtos` | Adaptar | 1, 3 | F1: `GET /providers` some (um provedor só). F3: entra o status do Ollama. `health` e a análise de repositórios ficam. |
| `ProjectDtos` | Adaptar | 1 | `PROVIDER` aceita só `ollama`. |
| `ProjectController`, `ProjectService`, `ProjectRepository` | Manter | 6 | Engenharia. |
| `PlanService`, `PlanRepository`, `ArtifactService`, `ArtifactRepository` | Manter | 6 | Engenharia. |
| `JobController`, `JobService`, `JobRepository` | Manter | — | Jobs da Engenharia. O chat usa streaming próprio. |
| `JobFailures` | Adaptar | 1, 3 | Saem as mensagens de Groq, chave e consentimento. Entram falhas específicas do Ollama (modelo ausente, contexto excedido). |
| `AttachmentController`, `AttachmentService`, `AttachmentRepository` | Manter / estender | 5 | Anexos de projeto continuam. O chat reaproveita o staging privado e a extração. |
| `PdfTextExtractor` | Manter | 5 | Reutilizado no chat, com os mesmos limites. |
| `RetentionJanitor` | Adaptar | 5 | Anexos do chat vivem enquanto a conversa existir (não expiram em 30 dias). |
| `AuthorizedRepositoryRegistry`, `RepositoryAnalysisService` | Manter / adaptar | 6 | Pasta autorizada pela UI (A12, P7). |
| `application.yml` | Adaptar | 1, 2 | F1: sai `docgen.remote`. F2: saem usuário e senha; `server.port: 0`; log em arquivo. |
| `V1__initial_schema.sql` | **Manter, imutável** | — | Regra do Flyway: nunca editar migração aplicada. |
| Novo pacote `com.docgen.desktop` | Planejado | 2 | `DesktopLauncher`, trava de instância, abertura do navegador, tokens de lançamento, diretórios por SO, encerramento. |
| Novos pacotes de Ollama, hardware, modelos e chat | Planejado | 3–5 | Seções 5 e 7. |
| `models/qwen-catalog.json` | Planejado | 3 | Catálogo versionado (seção 6.3). |

Testes da API:

| Teste | Ação | Fase | Motivo |
|---|---|---|---|
| `AuthAndSecurityTest` | Adaptar | 2 | Saem os testes de login, Basic e bloqueio progressivo. Ficam e se adaptam CSP, Host, CORS, Content-Type, rotas negadas e provedores sem credencial. Entram os testes da seção 4.2 do pedido (sem cookie = 401, Host inválido, `Origin` cruzado, token reutilizado). |
| `WorkflowApiTest` | Adaptar | 1 | Saem `remoteProvidersNeedOperationSpecificSingleUseConsent`, `consentBecomesStaleWhenTheProjectChanges` e `localProvidersDoNotIssueConsentTokens`. Os demais seguem com Ollama falso. |
| `ApiTestSupport`, `FakeLlm` | Adaptar | 1, 2 | F1: o falso deixa de ser "remoto". F2: os testes autenticam por sessão (token de lançamento → cookie) em vez de Basic. |
| `DocGenApiApplicationTest`, `ProjectApiTest`, `AttachmentApiTest`, `JobTimeoutTest` | Adaptar | 2 | Mesmo motivo: autenticação de teste. Os testes de isolamento entre donos continuam, porque o `owner` segue no esquema. |
| `AuthorizedRepositoryRegistryTest` | Manter | — | — |
| Novo `LocalOnlyArchitectureTest` | Planejado | 1 | Varre o código de produção de todos os módulos e falha com URI HTTP(S) fora do loopback, ou com classe de provedor que não seja o Ollama (ADR 0003). |

### 4.4 `docgen-web`

| Arquivo | Ação | Fase | Motivo |
|---|---|---|---|
| `views/login.js` | **Remover** | 2 | Sem login. |
| `components/consent.js` | **Remover** | 1 | Consentimento remoto. |
| `js/api.js` | Adaptar | 2, 4 | F2: sai o token em `sessionStorage`; requisições same-origin com cookie. F4: streaming (`fetch` + `ReadableStream`). |
| `js/main.js` | Adaptar | 2, 4 | F2: sem tela de login. F4: nova shell (sidebar com histórico, chat como tela inicial). |
| `js/router.js` | Adaptar | 2, 4, 6 | Sai `login`. Entram chat, onboarding e modelos. Projetos vão para Engenharia. |
| `js/markdown.js` | Adaptar | 4 | Botão copiar, realce leve sem dependência e renderização incremental durante o streaming. Continua sem interpretar HTML. |
| `js/url.js` | Adaptar | 4 | Não transformar em link clicável URLs de loopback ou rede privada vindas do modelo (mitiga R3). |
| `js/messages.js`, `js/validation.js` | Adaptar | 1 | Saem as mensagens de Groq e consentimento e a lista de provedores. |
| `components/activity.js`, `components/plan-view.js` | Adaptar | 1 | Rótulos de provedor. |
| `views/settings.js` | Adaptar | 2, 4 | Sai "Conta" e a chave do Groq. Entram tema, privacidade e "Apagar todos os meus dados". |
| `views/dashboard.js`, `projects.js`, `project-new.js`, `project-form.js`, `workspace.js`, `documents.js` | Adaptar | 1, 6 | F1: sem seleção de Groq nem diálogo de consentimento. F6: passam para a seção Engenharia. |
| `components/attachments.js` | Adaptar | 5 | Reaproveitado no composer do chat. |
| `components/diagram-view.js`, `docs-view.js`, `diagram.js`, `dom.js`, `format.js`, `icons.js`, `jobs.js`, `ui.js` | Manter | — | — |
| `css/app.css` | Adaptar | 4 | Design system em tokens, tema claro opcional e `@font-face` local. |
| `index.html` | Adaptar | 4 | Nova shell e fontes locais. |
| `favicon.svg` | Manter | — | — |
| Testes `api.test.mjs`, `markdown.test.mjs`, `security.test.mjs` | Adaptar | 2, 4 | Cookie no lugar do Bearer; casos de injeção na resposta do modelo; varredura também de URLs externas. |
| Demais testes JS | Manter | — | — |
| Novos: página de lançamento, chat, parser de stream, onboarding, modelos, fontes | Planejado | 2–4 | — |

### 4.5 Build, scripts, CI e documentação

| Arquivo | Ação | Fase | Motivo |
|---|---|---|---|
| `pom.xml` (raiz e módulos) | Adaptar | 1, 3 | F1: `3.0.0-SNAPSHOT`. F3: OSHI no `docgen-api` (só lá, para o SBOM da CLI não mudar). |
| `docgen-server` | Adaptar | 2 | Só para desenvolvimento: passa a apenas iniciar o launcher, sem variáveis de ambiente. |
| `install-web.sh` | Remover | 7 | Substituído pelos instaladores. Na F2, perde a exigência de variáveis de ambiente. |
| `install.sh` | Adaptar | 1 | Instalador da CLI: sai a dica do Groq. |
| `vercel.json` | Adaptar | 8 | Passa a servir só `site/`. |
| `.github/workflows/ci.yml` | Adaptar | 1, 7 | O teste de arquitetura entra pelo `mvn verify`. F7: matriz de SO para empacotamento. |
| `.github/workflows/release.yml` | Adaptar | 7 | Instaladores por SO, checksums e Sigstore. Publicar continua exigindo autorização. |
| `README.md`, `ARCHITECTURE.md`, `SECURITY.md` | Adaptar | 1, 9 | F1: tirar o Groq. F9: reescrita com o produto primeiro. |
| `CHANGELOG.md` | Adaptar | 1 → 9 | Entrada `[3.0.0]` com a quebra de compatibilidade. |
| `RELEASE_NOTES_v2.0.0.md`, `docs/images/*` | Manter (histórico) / revisar | 9 | — |

### 4.6 Branch `feat/docgen-desktop-launcher` (Em desenvolvimento, fora de `main`)

Ação proposta se a resposta à pergunta P1 for "seguir as decisões 4.1/4.2 a partir de `main`":

| Itens | Ação | Motivo |
|---|---|---|
| `docgen-desktop-protocol` (`DesktopBootstrap`, `DesktopMessage`, `DesktopProtocol`, `DesktopSession`) | Descartar | Não há processo filho para autenticar. |
| `BackendProcessManager`, `BackendConfiguration`, `BackendArtifact`, `BackendEndpoint`, `BackendLifecycleState`, `BackendStartupException`, `ReadinessVerifier`, `SessionPairingCoordinator`, `FlatJson` | Descartar | Modelo de dois processos. |
| `LauncherWindow`, `LauncherPanel`, `LauncherTheme`, `LauncherUi`, `LauncherViewState`, `ThemedComponents`, `SwingLauncherUi`, `LauncherController` | Descartar | Não há janela Swing. Ideias de estados e mensagens podem inspirar o splash e os erros de inicialização. |
| API: `DesktopConfig`, `DesktopEnvironmentPostProcessor`, `DesktopHandshakeController`, `DesktopLauncherLink`, `DesktopPairingController`, `DesktopPairingService`; web: `desktop.js`, `desktop-pairing.js` | Descartar | Substituídos pelo token de lançamento (ADR 0002). |
| `SingleInstanceLock`, `SecureDirectories`, `PlatformDetector`, `BrowserOpener` | **Reaproveitar** (F2) | Trava por arquivo do SO, diretórios privados sem symlink, detecção de SO e arquitetura, abertura do navegador sem shell. |
| `InstallationLayout` (diretórios por SO) | **Reaproveitar** (F2) | Já resolve XDG, macOS e Windows com caminhos absolutos e normalizados (ver P3 sobre `%LOCALAPPDATA%`). |
| `LogRedactor`, `BackendLogManager` | Reaproveitar ideias (F2) | Rotação e redação de log local. |
| `LauncherDiagnostics`, `RuntimeEnvironment` | Reaproveitar ideias (F3) | Diagnóstico de ambiente. |
| `BrandIcon` | Reaproveitar (F7) | Ícone gerado por código, sem binário versionado. |
| `packaging/linux/*`, `packaging/README.md` | Reaproveitar (F7) | `jlink` + `jpackage` já verificados no Linux; precisam virar um jar só. |
| `docs/desktop/INSTALLATION.md`, `TROUBLESHOOTING.md`, `DEVELOPMENT.md` | Reaproveitar trechos (F7, F9) | — |
| Testes de `SingleInstanceLock`, `InstallationLayout`, `PlatformDetector`, `LogRedactor` | Reaproveitar | Seguem as classes reaproveitadas. |

A branch não é apagada nem reescrita: fica como referência até você decidir.

## 5. Endpoints: hoje × planejado

Todos sob `/api/v1`, exceto quando indicado. Quando o pedido não fixou um nome, ele é proposta e pode
mudar na fase.

### Hoje em `main` (Implementado)

| Rota | Destino |
|---|---|
| `GET /health` | Manter |
| `GET /providers` | Remover (F1) |
| `POST /auth/token`, `GET /auth/session`, `DELETE /auth/token` | Remover (F2); substituídos pela sessão por cookie |
| `POST /repositories/{id}/analysis` | Manter (Engenharia, F6) |
| `POST/GET /projects`, `GET/PUT/DELETE /projects/{id}` | Manter (Engenharia) |
| `POST/GET /projects/{id}/attachments`, `DELETE …/{attachmentId}` | Manter |
| `POST /projects/{id}/transmission-previews` | Remover (F1) |
| `POST /projects/{id}/plans` (sem `consentToken`), `GET …/plans`, `GET …/plans/{planId}`, `POST …/approval` | Adaptar (F1) / Manter |
| `POST /projects/{id}/documentation` (sem `consentToken`) | Adaptar (F1) |
| `GET /jobs/{id}`, `POST /jobs/{id}/cancellation`, `GET /projects/{id}/jobs` | Manter |
| `GET /artifacts`, `GET /projects/{id}/artifacts`, `GET/PUT …/artifacts/{artifactId}`, `GET …/content`, `GET /projects/{id}/export` | Manter |

### Planejado

| Fase | Rota | Função |
|---|---|---|
| 2 | `GET /launch` (estático, fora de `/api`) | Página que lê o token do fragmento e o troca pela sessão. |
| 2 | `POST /session` · `GET /session` · `DELETE /session` | Troca do token de lançamento por cookie `HttpOnly`; estado da sessão; encerrar a sessão. |
| 2 | `POST /desktop/shutdown` | "Sair do DocGen" pela UI. |
| 2 | `POST /desktop/reopen` | Só para a segunda execução, autenticada pelo segredo de instância (ADR 0001). |
| 2 | `DELETE /data` | "Apagar todos os meus dados", com confirmação. |
| 3 | `GET /ollama/status` | Alcançável ou não, versão e porta. |
| 3 | `GET /hardware` | Diagnóstico; o que não for detectado vem como "não detectado". |
| 3 | `GET /models/catalog` | Catálogo Qwen com a classificação para esta máquina e o motivo. |
| 3 | `GET /models` · `GET /models/{nome}` · `GET /models/loaded` | `/api/tags`, `/api/show` e `/api/ps` do Ollama. |
| 3 | `POST /models/pulls` · `GET /models/pulls/{id}/events` · `DELETE /models/pulls/{id}` | Download (só tags do catálogo), progresso real e cancelamento. |
| 3 | `DELETE /models/{nome}` · `POST /models/{nome}/test` · `PUT /settings/default-model` | Remover, testar inferência e definir o modelo padrão. |
| 4 | `GET/POST /conversations` (com busca) · `GET/PATCH/DELETE /conversations/{id}` · `GET /conversations/{id}/export` | Conversas e exportação em Markdown. |
| 4 | `POST /conversations/{id}/messages` (resposta em stream) · `DELETE /conversations/{id}/generations/{gid}` | Geração em streaming e parar de verdade. |
| 4 | `GET/POST/PUT/DELETE /presets` | *System prompts* editáveis. |
| 5 | `POST/GET /conversations/{id}/attachments` · `DELETE …/{attachmentId}` | PDF, `.md` e `.txt` vinculados à conversa. |
| 6 | A definir com P7 | Autorizar uma pasta de projeto pela UI. |

Formato do streaming do chat: a escolha entre SSE (`SseEmitter`) e NDJSON (`StreamingResponseBody`) fica
para a Fase 4, justificada no relatório dela. Inclinação inicial: NDJSON por `fetch` + `ReadableStream`,
porque o envio é `POST` com corpo (o `EventSource` só faz `GET`), passa pelas mesmas regras de `Origin` e
Content-Type, usa o mesmo formato do Ollama e o parser é testável em `node --test`.

## 6. Diretrizes já verificadas para as próximas fases

### 6.1 API do Ollama (verificada em 2026-10-24)

- `POST /api/chat` com `stream: true`: blocos NDJSON com `message.content` e `done: false`. O último bloco
  traz `done: true`, `done_reason`, `prompt_eval_count` e `eval_count`. `keep_alive` padrão `5m`; `think`
  aceita booleano ou nível, e a mensagem pode trazer `thinking`.
- `POST /api/pull`: eventos `status` (`pulling manifest`, `pulling <digest>` com `digest`, `total` e
  `completed`, `verifying sha256 digest`, `writing manifest`, `removing any unused layers`, `success`).
  `completed` pode faltar no começo. **Não verificado**: se fechar a conexão cancela o download (R6).
- `GET /api/ps`: `name`, `model`, `size`, `size_vram`, `expires_at`, `details`.
- `POST /api/show`: `parameters`, `details.quantization_level`, `model_info` (o contexto vem numa chave com
  prefixo de arquitetura, por exemplo `<arquitetura>.context_length`) e `capabilities`.
- `DELETE /api/delete` com `{"model": …}`: 200 ou 404. `GET /api/version`: `{"version": …}`.
- Contexto padrão por VRAM: menos de 24 GiB → 4k; de 24 a 48 GiB → 32k; 48 GiB ou mais → 256k. O usuário
  muda com `OLLAMA_CONTEXT_LENGTH`; por requisição, com `options.num_ctx`.

### 6.2 Tags Qwen na biblioteca do Ollama (verificadas em 2026-10-24)

Famílias encontradas na busca da biblioteca: `qwen3.8` (27b), `qwen3.6` (27b, 35b), `qwen3.5` (0.8b a 122b,
texto e imagem, 256K), `qwen3` (0.6b a 235b), `qwen3-coder` (30b, 480b), `qwen2.5-coder` (0.5b a 32b),
`qwen2.5`, `qwen3-vl` e outras. Amostra das páginas de tags:

| Tag | Tamanho na página | Contexto | Observação |
|---|---|---|---|
| `qwen3.5:0.8b` / `:2b` / `:4b` / `:9b` | 1,2–1,3 / 2,7–3,1 / 3,3–4,0 / 6,6–7,6 GB | 256K | `latest` = `9b`. Os tamanhos aparecem como **faixa** (provavelmente variantes por plataforma; a verificar). |
| `qwen3:0.6b` / `:1.7b` / `:4b` / `:8b` / `:14b` | 523 MB / 1,4 / 2,5 / 5,2 / 9,3 GB | 40K (4b: 256K) | `latest` = `8b`. O digest das tags padrão coincide com o das variantes `q4_K_M` (inferência pelo digest). |
| `qwen2.5-coder:1.5b` / `:3b` / `:7b` / `:14b` | 986 MB / 1,9 / 4,7 / 9,0 GB | 32K | Mesma inferência de `q4_K_M`. |
| `qwen3-coder:30b` | 19 GB | 256K | `latest` = `30b` (MoE `a3b`). |

O catálogo da Fase 3 só leva tags conferidas na fase, com a data da verificação e `"verified": true`. O que
não puder ser conferido entra com `"verified": false`, e eu aviso.

### 6.3 Catálogo e recomendação (Planejado, Fase 3)

- Fonte única: `docgen-api/src/main/resources/models/qwen-catalog.json`, com versão do esquema e data de
  verificação.
- Estimativa de memória ≈ pesos quantizados (tamanho em disco) + KV cache (2 × camadas × cabeças KV ×
  dimensão da cabeça × bytes por elemento × `num_ctx`) + sobrecarga do runtime. Os parâmetros de arquitetura
  vêm do catálogo, conferidos com o `model_info` do `/api/show`. A fórmula e as premissas ficam documentadas
  no código e num ADR da fase.
- Classes: Recomendado / Funciona com offload / Só CPU / Não recomendado, com o motivo em linguagem simples.
- Lógica pura e determinística, testada com os perfis do pedido (CPU e 8 GB, GPU de 6 GB, GPU de 12 GB, Apple
  Silicon com 16 GB, máquina com 4 GB).

### 6.4 `jpackage` do JDK 21 (verificado em 2026-10-24)

Tipos: `app-image`, `exe`, `msi`, `rpm`, `deb`, `pkg`, `dmg`. Cada formato é gerado no próprio SO, sem
compilação cruzada. Pré-requisitos: `rpm-build` (RPM), `fakeroot` (Ubuntu/deb), WiX 3.0 ou superior
(Windows) e as ferramentas de linha de comando do Xcode quando houver `--mac-sign` ou ícone de DMG.

## 7. Ordem de execução

As fases seguem a ordem do pedido. Cada uma termina com `mvn -B verify` e `node --test` verdes e com o
relatório de fase, e só avança com aprovação.

| Fase | Entrega | Depende de | Migração | Ajuste proposto em relação ao pedido |
|---|---|---|---|---|
| 0 | Este roadmap, três ADRs, linha de base | — | — | — |
| 1 | Somente local: sem Groq nem fronteira remota; Ollama fixo em loopback; teste de arquitetura; `3.0.0-SNAPSHOT` | 0 | `V2`: projetos com `groq` passam para `ollama` e modelo padrão (planos e jobs mantêm o provedor histórico, só para exibição) | Incluir a correção A1 (`num_ctx` explícito e falha fechada quando o prompt não couber), porque a CLI só "gera README com Ollama" de verdade com ela (P5). |
| 2 | Launcher, instância única, porta livre, token de lançamento → cookie, CSRF por `Origin`, diretórios por SO, apagar dados, log local | 1 | `V3`: `owner` passa a ser `local` | Desligamento por inatividade e bandeja opcional (ADR 0001, P2). |
| 3 | Cliente Ollama completo, `HardwareProbe` (OSHI + sondas), catálogo, recomendador, telas de onboarding e Modelos | 2 | — | Bloqueio de modelos de nuvem do Ollama (A10). |
| 4 | Chat com streaming e cancelamento, histórico persistido, presets, nova shell e design system | 3 | `V4`: conversas, mensagens, presets | O pedido chama de "V2"; a numeração muda porque as fases 1 e 2 também migram. |
| 5 | Anexos PDF, `.md` e `.txt` no chat | 4 | `V5`: anexos de conversa | Guardar só o texto extraído, como hoje, e não o arquivo bruto (P4). |
| 6 | Engenharia na sidebar, ligada ao chat | 5 | se precisar | Autorizar pasta de repositório pela UI (P7). |
| 7 | Instaladores (`jpackage` + `jlink`) e workflow em matriz | 6 | — | — |
| 8 | Site estático em `site/` na Vercel | 7 (links dos assets reais) | — | Neutralizar a publicação atual antes (P8). |
| 9 | Documentação e roteiro E2E manual | 8 | — | — |

## 8. Riscos

| # | Risco | Impacto | Mitigação | Fase |
|---|---|---|---|---|
| R1 | Truncamento silencioso do contexto pelo Ollama (A1) | Alto: regras de segurança e instruções perdidas, saída ruim | `num_ctx` explícito, estimativa de tokens com folga, falha fechada quando não couber, corte de histórico com aviso no chat | 1, 4 |
| R2 | O token de lançamento aparece na linha de comando do navegador (`/proc/<pid>/cmdline` é legível por outros usuários do Linux por padrão) até ser trocado | Médio: em máquina compartilhada, outro usuário pode tentar usar o token antes do navegador | Uso único, validade curta, detecção de colisão (token já usado → revoga a sessão criada com ele e avisa) (ADR 0002) | 2 |
| R3 | Cookies não são isolados por porta (RFC 6265, §8.5): outro serviço em `127.0.0.1:<outra porta>` recebe o cookie quando o navegador o acessa | Médio | `SameSite=Strict`, sessão que morre com o app, nada de link clicável para loopback ou rede privada na resposta do modelo (ADR 0002) | 2, 4 |
| R4 | A branch do launcher diverge das decisões e já está publicada | Médio: trabalho duplicado | Decidir P1 antes da Fase 2; reaproveitar o que a seção 4.6 lista | 2 |
| R5 | Modelos de nuvem do Ollama (A10) | Alto: violaria o princípio 1 | Download só de tags do catálogo; bloquear modelo detectado como de nuvem (método a verificar na F3); orientar sobre `OLLAMA_NO_CLOUD` | 3 |
| R6 | Cancelar o `/api/pull` pode não interromper o download no Ollama | Médio | Testar com o Ollama real na F3, sem afirmar nada antes | 3 |
| R7 | O catálogo envelhece rápido (famílias novas a cada poucos meses) e os tamanhos variam por plataforma | Médio | Catálogo versionado com data e `verified`; a UI mostra o tamanho informado pelo Ollama durante o download | 3 |
| R8 | VRAM livre não é exposta pelo OSHI; sem `nvidia-smi` (AMD, Intel, integradas) a VRAM fica desconhecida; Apple Silicon usa memória unificada | Médio | "Não detectado" em vez de inventar; classificar de forma conservadora quando faltar dado; sondas sem shell e com timeout | 3 |
| R9 | A estimativa de memória erra (KV cache depende da arquitetura; modelos de visão têm projetor; o Ollama pode quantizar o KV cache) | Médio | Fórmula documentada com folga; comparar com `size`/`size_vram` do `/api/ps` depois de carregar | 3 |
| R10 | Empacotamento: cada SO gera o próprio instalador; MSI do JDK 21 exige WiX 3; sem certificado, SmartScreen e Gatekeeper alertam; não há notarização da Apple | Médio | Matriz no GitHub Actions; documentação honesta de como prosseguir; disponibilidade do WiX nos runners a verificar na F7 | 7 |
| R11 | Navegador: o modo `--app` do Chromium e os navegadores em Snap/Flatpak se comportam de jeitos diferentes | Baixo | Tentativa com fallback para o navegador padrão; testar nos três SOs | 2, 7 |
| R12 | O Spring Boot leva alguns segundos para subir sem nenhuma resposta visual, e o usuário clica de novo | Baixo | Instância única antes do Spring; splash nativo da JVM (`-splash`) avaliado na F2 | 2 |
| R13 | Reescrever a autenticação dos testes pode enfraquecer a cobertura de segurança | Médio | Mapear teste antigo para teste novo no relatório da F2; nenhum teste de segurança sai sem substituto | 2 |
| R14 | Dados locais ficam sem criptografia em repouso (H2 em arquivo) | Baixo | Diretório `0700`; documentar que a criptografia de disco é do SO | 2, 9 |
| R15 | Ambiente de desenvolvimento sem JDK 21 (só JBR 25 e OpenJDK 27) | Baixo | Build com `--release 21`; a CI com Temurin 21 continua sendo a referência | todas |
| R16 | Com a UI no navegador, fechar a aba não encerra o processo, e o usuário pode não perceber que o app segue rodando | Médio | Ícone de bandeja quando houver suporte; desligamento por inatividade quando não houver aba conectada nem download ou geração em andamento (P2) | 2 |

## 9. Perguntas para decidir

1. **P1, branch do launcher.** Proponho seguir as decisões 4.1/4.2 a partir de `main` e reaproveitar só os
   itens da seção 4.6. A alternativa é partir da branch existente. A comparação está no ADR 0001.
2. **P2, ciclo de vida.** Posso implementar o desligamento por inatividade (sem aba conectada por 10 min e
   nada em andamento), além do "Sair" e da bandeja opcional?
3. **P3, Windows.** Proponho `%LOCALAPPDATA%\DocGen` em vez de `%APPDATA%\DocGen`: o `AppData\Roaming` é
   sincronizado com o servidor em perfis móveis de domínio, o que levaria conversas para fora do computador.
4. **P4, anexos.** Proponho manter a política atual: só o texto extraído e redigido fica salvo, e os bytes
   brutos são apagados depois da extração. Ou você quer o arquivo original guardado junto com a conversa?
5. **P5, correção A1 na Fase 1.** Posso incluir `num_ctx` explícito e falha fechada já na Fase 1?
6. **P6, modelo padrão da CLI.** Candidatos verificados: `qwen2.5-coder:7b` (4,7 GB, 32K, sem *thinking*,
   orientado a código), `qwen3:8b` (5,2 GB, 40K, com *thinking*) e `qwen3.5:9b` (6,6–7,6 GB, 256K). Proponho
   `qwen2.5-coder:7b`, com o prompt de repositório limitado ao contexto do modelo.
7. **P7, repositórios no app.** Para "gere um README deste projeto" sem terminal, o backend pode abrir o
   seletor de pastas nativo do SO e registrar a pasta escolhida como autorizada. Isso entra na Fase 6?
8. **P8, Vercel.** Posso já, na Fase 1, trocar o `vercel.json` para não publicar o app (página mínima
   provisória), antes do site da Fase 8?
9. **P9, fluxo de branches.** Cada fase sai de `main`, mas eu não faço push nem merge sem você. Prefere fazer
   o merge de cada fase em `main` antes da seguinte, ou que eu crie a próxima fase a partir da branch da fase
   anterior?

## 10. Fontes consultadas (2026-10-24)

- API do Ollama: <https://github.com/ollama/ollama/blob/main/docs/api.md> (avisa que a documentação está
  migrando para <https://docs.ollama.com/api>).
- Contexto: <https://docs.ollama.com/context-length>. Nuvem e modo só local: <https://docs.ollama.com/cloud>,
  <https://docs.ollama.com/faq>.
- Biblioteca: <https://ollama.com/search?q=qwen>, <https://ollama.com/library/qwen3.5/tags>,
  <https://ollama.com/library/qwen3/tags>, <https://ollama.com/library/qwen2.5-coder/tags>,
  <https://ollama.com/library/qwen3-coder/tags>.
- `jpackage` 21: <https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html> e
  <https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html>.
- Cookies e portas: RFC 6265, §8.5 ("Weak Confidentiality").
- Medições locais: `mvn -B clean verify` e `node --test` nas duas branches; sonda de contexto no Ollama
  0.32.11 com `llama3.2:latest` (local, sem download, modelo descarregado em seguida com `keep_alive: 0`).
