# DocGen Desktop: roadmap de realinhamento

> **DocGen: seu assistente local e gratuito. Sua IA. Seu computador. Suas regras.**

| | |
|---|---|
| Fase | 0: auditoria e plano (sem código de produção) |
| Data | 2026-10-24 |
| Branch | `feat/desktop-fase0-auditoria`, criada a partir de `main` (`9d65fc1`) |
| Estado deste documento | **Aprovado** em 2026-10-24, com as decisões registradas na §9. Fases 1 e 2 concluídas; Fase 3 implementada e aguardando aprovação (§12) |
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
| A2 | **Já existe um launcher desktop numa branch publicada**, com desenho diferente das decisões 4.1/4.2: janela Swing num processo separado, backend como processo filho, protocolo HMAC por stdin/stdout e pareamento do navegador com código confirmado na janela. | `feat/docgen-desktop-launcher`: 13 commits à frente de `main`, também em `origin`; 97 arquivos, +5.445 linhas de produção (4.445 em `docgen-launcher` e `docgen-desktop-protocol`, 1.000 em API e web) e +3.533 de testes. | Decidido (P1): seguir 4.1/4.2 a partir de `main` e aproveitar só a trava de instância, os diretórios privados, a abertura do navegador e os scripts `jlink` (seção 4.6). A branch não é alterada. |
| A3 | O `OllamaProvider` depende de `GroqProvider.boundedUtf8BodySubscriber`. | `OllamaProvider.java:101` | Antes de apagar o Groq, o leitor limitado de corpo precisa ir para uma classe própria. |
| A4 | O banco pode ter linhas com `provider = 'groq'` (projetos, planos, jobs). | `V1__initial_schema.sql`, `ProjectDtos.PROVIDER = "groq\|ollama"` | Projetos antigos quebrariam o `ProviderSelection` depois da remoção. É preciso uma migração nova (seção 7). |
| A5 | O app não sobe sem `DOCGEN_API_USERNAME`/`DOCGEN_API_PASSWORD` (`@NotBlank`). Toda linha do banco tem `owner` = usuário do login. | `DocGenApiProperties.Api`, `SecurityConfig.userDetailsService` | Sem login, o dono passa a ser fixo (proposta: `local`), com migração dos donos existentes. O escopo por `owner` continua como camada de defesa. |
| A6 | O diretório de dados é XDG em **todos** os SOs (no Windows, cai em `~\.local\share\docgen`). | `StorageConfig.resolveDataDirectory` | Precisa virar caminho por SO (Fase 2). No Linux, o caminho atual se mantém e os dados existentes continuam válidos. |
| A7 | A Vercel publica o **frontend do app** (`docgen-web/src/main/resources/static`), que não funciona sem o backend. | `vercel.json` | Pode confundir quem chega ao site. Decidido (P8): na Fase 1 o `vercel.json` passa a servir `site/` com uma página estática mínima; o site completo fica na Fase 8. |
| A8 | Na CLI, o provedor padrão é `groq` e o modelo padrão do Ollama é `llama3.2` (não é Qwen). | `DocGenCLI`, `LLMProviderFactory` | Decidido (P6): na Fase 1 o padrão vira Ollama com `qwen2.5-coder:7b`. Sem o modelo instalado, a CLI falha com mensagem amigável e não baixa nada sozinha. |
| A9 | O `SecretRedactor` tem um padrão para chaves `gsk_…` (formato do Groq). | `SecretRedactor.java:21` | Isso é **defesa** (redige segredos nos dados do usuário), não integração. Proposta: manter o padrão, com comentário neutro. Remover enfraqueceria a redação (princípio 5). |
| A10 | O Ollama tem **modelos de nuvem**: a instância local pode falar com ollama.com. A FAQ oficial permite desligar com `OLLAMA_NO_CLOUD=1` ou `disable_ollama_cloud` em `~/.ollama/server.json`. A documentação não diz como um cliente local identifica um modelo de nuvem. | docs.ollama.com/cloud, docs.ollama.com/faq | Um modelo de nuvem escolhido no DocGen tiraria a inferência do computador **pelo próprio Ollama**. O DocGen precisa bloquear isso (ADR 0003, Fase 3). |
| A11 | O Ollama 0.32.11 local devolve, em `/api/tags`, campos que a `api.md` não documenta (`details.context_length`, `capabilities`). A própria `api.md` avisa que a documentação está migrando para docs.ollama.com. | `curl /api/tags` local; `docs/api.md` | O cliente da Fase 3 deve tratar campos opcionais e ser testado contra a versão real, não só contra a documentação. |
| A12 | Gerar README/ARCHITECTURE **a partir de um repositório** só existe na CLI. A API só *analisa* repositórios mapeados pelo operador em `docgen.repositories`, que é configuração de arquivo ou ambiente. | `RepositoryAnalysisService`, `ApiController` | Decidido (P7), na Fase 6: a pasta é autorizada pela UI e revogável. O navegador não fornece o caminho real, então vale o diálogo nativo aberto pelo processo Java ou um caminho digitado e validado pelas mesmas regras do `RepoScanner`. |
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
| `RequestPolicyFilter` | Adaptar | 2, 5 | F2: validar `Origin` e `Sec-Fetch-Site` em métodos que alteram estado, como defesa em profundidade (não há credencial ambiente). F5: tipos `text/markdown` e `text/plain` para anexos do chat. Os limites de tamanho continuam. |
| `SecurityConfig` | Adaptar | 2 | Sai Basic, `UserDetailsService`, `PasswordEncoder` e `AuthenticationManager`. O Bearer passa a valer só para tokens de sessão nascidos da troca do token de lançamento. `/launch` fica público. A CSP continua igual. |
| `AuthTokenService` | Adaptar | 2 | Vira o repositório de sessões (só o hash, em memória) e de tokens de lançamento de uso único. |
| `BearerTokenAuthenticationFilter` | Manter | 2 | Continua lendo só `Authorization: Bearer`. Nenhum cookie é criado nem lido (ADR 0002). |
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
| `AuthAndSecurityTest` | Adaptar | 2 | Saem os testes de login, Basic e bloqueio progressivo. Ficam e se adaptam CSP, Host, CORS, Content-Type, rotas negadas e provedores sem credencial. Entram os testes do ADR 0002 (sem token = 401, nenhuma resposta com `Set-Cookie`, Host inválido, `Origin` cruzado, token reutilizado). |
| `WorkflowApiTest` | Adaptar | 1 | Saem `remoteProvidersNeedOperationSpecificSingleUseConsent`, `consentBecomesStaleWhenTheProjectChanges` e `localProvidersDoNotIssueConsentTokens`. Os demais seguem com Ollama falso. |
| `ApiTestSupport`, `FakeLlm` | Adaptar | 1, 2 | F1: o falso deixa de ser "remoto". F2: os testes autenticam por sessão (token de lançamento → Bearer) em vez de Basic. |
| `DocGenApiApplicationTest`, `ProjectApiTest`, `AttachmentApiTest`, `JobTimeoutTest` | Adaptar | 2 | Mesmo motivo: autenticação de teste. Os testes de isolamento entre donos continuam, porque o `owner` segue no esquema. |
| `AuthorizedRepositoryRegistryTest` | Manter | — | — |
| Novo `LocalOnlyArchitectureTest` | Planejado | 1 | Varre o código de produção de todos os módulos e falha com URI HTTP(S) fora do loopback, ou com classe de provedor que não seja o Ollama (ADR 0003). |

### 4.4 `docgen-web`

| Arquivo | Ação | Fase | Motivo |
|---|---|---|---|
| `views/login.js` | **Remover** | 2 | Sem login. |
| `components/consent.js` | **Remover** | 1 | Consentimento remoto. |
| `js/api.js` | Adaptar | 2, 4 | F2: o Bearer continua só no `sessionStorage`, mas vem da troca do token de lançamento (sem login e sem cookie). F4: streaming (`fetch` + `ReadableStream`, com o cabeçalho `Authorization`). |
| `js/main.js` | Adaptar | 2, 4 | F2: sem tela de login. F4: nova shell (sidebar com histórico, chat como tela inicial). |
| `js/router.js` | Adaptar | 2, 4, 6 | Sai `login`. Entram chat, onboarding e modelos. Projetos vão para Engenharia. |
| `js/markdown.js` | Adaptar | 4 | Botão copiar, realce leve sem dependência e renderização incremental durante o streaming. Continua sem interpretar HTML. |
| `js/messages.js`, `js/validation.js` | Adaptar | 1 | Saem as mensagens de Groq e consentimento e a lista de provedores. |
| `components/activity.js`, `components/plan-view.js` | Adaptar | 1 | Rótulos de provedor. |
| `views/settings.js` | Adaptar | 2, 4 | Sai "Conta" e a chave do Groq. Entram tema, privacidade e "Apagar todos os meus dados". |
| `views/dashboard.js`, `projects.js`, `project-new.js`, `project-form.js`, `workspace.js`, `documents.js` | Adaptar | 1, 6 | F1: sem seleção de Groq nem diálogo de consentimento. F6: passam para a seção Engenharia. |
| `components/attachments.js` | Adaptar | 5 | Reaproveitado no composer do chat. |
| `components/diagram-view.js`, `docs-view.js`, `diagram.js`, `dom.js`, `format.js`, `icons.js`, `jobs.js`, `ui.js`, `url.js` | Manter | — | — |
| `css/app.css` | Adaptar | 4 | Design system em tokens, tema claro opcional e `@font-face` local. |
| `index.html` | Adaptar | 4 | Nova shell e fontes locais. |
| `favicon.svg` | Manter | — | — |
| Testes `api.test.mjs`, `markdown.test.mjs`, `security.test.mjs` | Adaptar | 1, 2, 4 | F1: varredura também de URLs externas. F2: token vindo da troca de lançamento. F4: casos de injeção na resposta do modelo. |
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

Decisão P1: seguir as decisões 4.1/4.2 a partir de `main` e aproveitar só a trava de instância, os diretórios privados, a abertura do navegador e os scripts `jlink`:

| Itens | Ação | Motivo |
|---|---|---|
| `docgen-desktop-protocol` (`DesktopBootstrap`, `DesktopMessage`, `DesktopProtocol`, `DesktopSession`) | Descartar | Não há processo filho para autenticar. |
| `BackendProcessManager`, `BackendConfiguration`, `BackendArtifact`, `BackendEndpoint`, `BackendLifecycleState`, `BackendStartupException`, `ReadinessVerifier`, `SessionPairingCoordinator`, `FlatJson` | Descartar | Modelo de dois processos. |
| `LauncherWindow`, `LauncherPanel`, `LauncherTheme`, `LauncherUi`, `LauncherViewState`, `ThemedComponents`, `SwingLauncherUi`, `LauncherController` | Descartar | Não há janela Swing. Ideias de estados e mensagens podem inspirar o splash e os erros de inicialização. |
| API: `DesktopConfig`, `DesktopEnvironmentPostProcessor`, `DesktopHandshakeController`, `DesktopLauncherLink`, `DesktopPairingController`, `DesktopPairingService`; web: `desktop.js`, `desktop-pairing.js` | Descartar | Substituídos pelo token de lançamento (ADR 0002). |
| `SingleInstanceLock`, `SecureDirectories`, `PlatformDetector`, `BrowserOpener` | **Reaproveitar** (F2) | Trava por arquivo do SO, diretórios privados sem symlink, detecção de SO e arquitetura, abertura do navegador sem shell. |
| `InstallationLayout` (diretórios por SO) | **Reaproveitar** (F2) | Já resolve XDG, macOS e Windows com caminhos absolutos e normalizados (no Windows, `%LOCALAPPDATA%\DocGen`, decisão P3). |
| `packaging/linux/*`, `packaging/README.md` | Reaproveitar (F7) | `jlink` + `jpackage` já verificados no Linux; precisam virar um jar só. |
| Testes de `SingleInstanceLock`, `InstallationLayout`, `PlatformDetector` | Reaproveitar | Seguem as classes reaproveitadas. |
| `LogRedactor`, `BackendLogManager`, `LauncherDiagnostics`, `RuntimeEnvironment`, `BrandIcon`, `docs/desktop/*` | Não reaproveitar | Fora da lista aprovada em P1. O log local, o diagnóstico e o ícone são escritos nas próprias fases. |

A branch não é alterada, apagada nem reescrita (P1).

## 5. Endpoints: hoje × planejado

Todos sob `/api/v1`, exceto quando indicado. Quando o pedido não fixou um nome, ele é proposta e pode
mudar na fase.

### Hoje em `main` (Implementado)

| Rota | Destino |
|---|---|
| `GET /health` | Manter |
| `GET /providers` | Remover (F1) |
| `POST /auth/token`, `GET /auth/session`, `DELETE /auth/token` | Remover (F2); substituídos pela troca do token de lançamento |
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
| 2 | `POST /session` · `GET /session` · `DELETE /session` | Troca do token de lançamento por token de sessão Bearer (sem cookie); estado da sessão; encerrar a sessão. |
| 2 | `POST /desktop/shutdown` · `POST /desktop/heartbeat` | "Sair do DocGen" pela UI; sinal de presença da UI (ADR 0001). |
| 2 | `POST /desktop/reopen` | Só para a segunda execução, autenticada pelo segredo de instância (ADR 0001). |
| 2 | `DELETE /data` | "Apagar todos os meus dados", com confirmação. |
| 3 ✔ | `GET /ollama/status` | Alcançável ou não, versão e endereço. **Implementado.** |
| 3 ✔ | `GET /hardware` | Diagnóstico (`null` = não detectado) e status do Ollama. **Implementado.** |
| 3 ✔ | `GET /models/catalog` | Catálogo Qwen com classe, motivo, estimativa e sugestões. **Implementado.** |
| 3 ✔ | `GET /models` · `GET /models/details?name=` | `/api/tags` com o `/api/ps` embutido (a rota `/models/loaded` não foi necessária) e `/api/show`. O nome vai na query porque uma tag pode ter `/`. **Implementado.** |
| 3 ✔ | `POST /models/pulls` · `GET /models/pulls` · `GET /models/pulls/{id}` · `DELETE /models/pulls/{id}` | Download (só tags do catálogo), progresso real por *polling* do instantâneo (em vez de `…/events`) e cancelamento. **Implementado.** |
| 3 ✔ | `DELETE /models?name=` · `POST /models/test` · `GET /settings` · `PUT /settings/default-model` · `PUT /settings/onboarding` | Remover, testar inferência, preferências (modelo padrão e fim da configuração inicial). **Implementado.** |
| 4 | `GET/POST /conversations` (com busca) · `GET/PATCH/DELETE /conversations/{id}` · `GET /conversations/{id}/export` | Conversas e exportação em Markdown. |
| 4 | `POST /conversations/{id}/messages` (resposta em stream) · `DELETE /conversations/{id}/generations/{gid}` | Geração em streaming e parar de verdade. |
| 4 | `GET/POST/PUT/DELETE /presets` | *System prompts* editáveis. |
| 5 | `POST/GET /conversations/{id}/attachments` · `DELETE …/{attachmentId}` | PDF, `.md` e `.txt` vinculados à conversa. |
| 6 | A definir com P7 | Autorizar uma pasta de projeto pela UI. |

Streaming do chat: o transporte está decidido, `fetch` + `ReadableStream`, porque precisa do cabeçalho
`Authorization` (o `EventSource` não permite cabeçalhos) e de `POST` com corpo. O formato do corpo, NDJSON
(`StreamingResponseBody`) ou eventos SSE lidos pelo `fetch` (`SseEmitter`), fica para a Fase 4, justificado no
relatório dela. A inclinação é NDJSON: é o formato do Ollama e o parser é testável em `node --test`.

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

O catálogo da Fase 3 inclui as famílias novas (`qwen3.5` etc.) **só com tags verificadas** na fase, com a data
da verificação e `"verified": true`. O que não puder ser conferido entra com `"verified": false`, e eu aviso.

### 6.3 Catálogo e recomendação (Implementado na Fase 3; detalhes no [ADR 0004](../adr/0004-catalogo-e-recomendacao-de-modelos.md))

- Fonte única: `docgen-api/src/main/resources/models/qwen-catalog.json`, com versão do esquema e data de
  verificação.
- Quando a página da biblioteca informa o tamanho como faixa (por exemplo "6,6 GB – 7,6 GB"), a estimativa
  de memória usa o **limite superior**.
- Estimativa de memória ≈ pesos quantizados (tamanho em disco) + KV cache (2 × camadas × cabeças KV ×
  dimensão da cabeça × bytes por elemento × `num_ctx`) + sobrecarga do runtime. Os parâmetros de arquitetura
  vêm do catálogo, conferidos com o `model_info` do `/api/show`. A fórmula e as premissas ficam documentadas
  no código e num ADR da fase.
- Classes: Recomendado / Funciona com offload / Só CPU / Não recomendado, com o motivo em linguagem simples.
- Lógica pura e determinística, testada com os perfis do pedido (CPU e 8 GB, GPU de 6 GB, GPU de 12 GB, Apple
  Silicon com 16 GB, máquina com 4 GB).
- O DocGen oferece downloads somente para tags do catálogo. Na Fase 6, a Engenharia também poderá usar qualquer
  modelo já instalado que seja local, mesmo fora do catálogo; a recusa de modelos remotos continua obrigatória.

### 6.4 Modelos com modo de raciocínio (requisito registrado na decisão P6)

Modelos Qwen3 em diante podem emitir raciocínio (*thinking*), seja no campo `thinking` do Ollama, seja dentro
do texto. Antes de qualquer saída de um modelo desses chegar ao `DocumentationValidator`, ao parser do plano
ou à tela, o raciocínio precisa ser tratado de forma explícita: separado, descartado ou exibido à parte, nunca
publicado misturado à documentação. Isso vale para a CLI com `--model`, para a Engenharia e para o chat, e
precisa estar resolvido na primeira fase que oferecer esses modelos (o catálogo da Fase 3). O padrão da CLI,
`qwen2.5-coder:7b`, não tem modo de raciocínio.

**Implementado na Fase 3:** `think: false` vai só a modelos com a capacidade `thinking` (o Ollama 0.32.11 recusa
`think: true` nos demais com HTTP 400); o campo `thinking` do Ollama nunca entra no texto; e o `ThinkingFilter`
tira um bloco `<think>…</think>` inicial escrito pelo próprio modelo, mesmo quebrado entre pedaços do stream. Na
geração de documentação, o raciocínio é descartado antes do `DocumentationValidator` e do parser do plano; no chat,
vai para um canal separado (`onThinking`), para a Fase 4 decidir como mostrar.

### 6.5 `jpackage` do JDK 21 (verificado em 2026-10-24)

Tipos: `app-image`, `exe`, `msi`, `rpm`, `deb`, `pkg`, `dmg`. Cada formato é gerado no próprio SO, sem
compilação cruzada. Pré-requisitos: `rpm-build` (RPM), `fakeroot` (Ubuntu/deb), WiX 3.0 ou superior
(Windows) e as ferramentas de linha de comando do Xcode quando houver `--mac-sign` ou ícone de DMG.

### 6.6 Janela de contexto (registrado na aprovação da Fase 1)

A Fase 1 implementou o `num_ctx` explícito: estimativa conservadora do prompt (`PromptTokens`) mais 8.192
tokens de reserva para a resposta, com teto no contexto máximo do modelo e falha fechada quando não couber.
Ficam registrados:

- **O estimador é calibrado para a família Qwen** (tokenizador Qwen2.5, contagens medidas no Ollama 0.32.11).
  Para outras famílias de modelo, a estimativa é **melhor esforço**; a checagem do `prompt_eval_count` segue
  como segunda barreira.
- **Fase 3 (feito):** `ConnectException` (Ollama fora do ar) vira `OllamaUnavailableException` e é classificada
  como `provider_unavailable`; a CLI explica como abrir o Ollama ou ajustar `--ollama-port`.
- **Fase 4, chat:** o `num_ctx` deve ser **estável por conversa** e subir só em degraus (4k → 8k → 16k → 32k,
  limitado ao máximo do modelo), porque cada mudança de `num_ctx` faz o Ollama recarregar o modelo. O degrau
  sobe quando o histórico mais a reserva de saída não cabem mais, e o corte de histórico antigo, com aviso
  visível, só entra quando o degrau máximo foi atingido.
- **Fase 6, orçamento da varredura:** o orçamento de conteúdo do `RepoScanner` passa a ser derivado do
  contexto do modelo menos a reserva de saída (e menos as regras do prompt), em vez dos 120.000 caracteres
  fixos. Os arquivos são priorizados (README, arquivos de build, pontos de entrada antes do resto), e o
  relatório mostra o que ficou de fora e por quê. O limite só restringe, nunca amplia, a seleção atual.

## 7. Ordem de execução

As fases seguem a ordem do pedido. Cada uma termina com `mvn -B verify` e `node --test` verdes e com o
relatório de fase, e só avança com aprovação.

Regras permanentes do processo: nenhuma ação de rede ou download na máquina do usuário (inclusive consultas a
registros ou `pull` de modelos) sem autorização prévia. Toda fase atualiza a tabela de status do `README.md`,
distinguindo Implementado, Em desenvolvimento e Planejado, antes do relatório final.

| Fase | Entrega | Depende de | Migração | Ajuste proposto em relação ao pedido |
|---|---|---|---|---|
| 0 | Este roadmap, três ADRs, linha de base | — | — | — |
| 1 | Somente local: sem Groq nem fronteira remota; Ollama fixo em loopback; teste de arquitetura; `3.0.0-SNAPSHOT` | 0 | `V2`: projetos com `groq` passam para `ollama` e modelo padrão (planos e jobs mantêm o provedor histórico, só para exibição) | Correção A1 em commit próprio, com teste de regressão do truncamento (P5); modelo padrão `qwen2.5-coder:7b` sem download automático (P6); `vercel.json` → `site/` mínimo (P8). |
| 2 | Launcher, instância única, porta livre, token de lançamento → Bearer em `sessionStorage`, validação de `Origin`/`Sec-Fetch-Site`, diretórios por SO, apagar dados, log local | 1 | `V3`: `owner` passa a ser a constante `local`, e a coluna é mantida | Heartbeat com desligamento após 10 min sem sinal, nunca com download ou geração em andamento (P2); `%LOCALAPPDATA%\DocGen` no Windows (P3); sessão com 12 h de inatividade, só em memória; bandeja opcional. |
| 3 | Cliente Ollama completo, `HardwareProbe` (OSHI + sondas), catálogo, recomendador, telas de onboarding e Modelos | 2 | `V4`: preferências locais (modelo padrão, fim do onboarding) | Defesa em camadas contra modelos de nuvem (ADR 0003, A10); tratamento do raciocínio dos modelos Qwen3+ (§6.4); `ConnectException` passa a ser classificado como `provider_unavailable` (§6.6). |
| 4 | Chat com streaming e cancelamento, histórico persistido, presets, nova shell e design system | 3 | `V5`: conversas, mensagens, presets | O pedido chama de "V2"; a numeração muda porque as fases 1, 2 e 3 também migram. O `num_ctx` do chat é estável por conversa e sobe em degraus (§6.6). |
| 5 | Anexos PDF, `.md` e `.txt` no chat | 4 | `V6`: anexos de conversa | Guardar só o texto extraído, mais nome, tamanho e SHA-256 do original; os bytes brutos não ficam salvos (P4). |
| 6 | Engenharia na sidebar, ligada ao chat | 5 | se precisar | Pasta de repositório autorizada pela UI e revogável: diálogo nativo do processo Java ou caminho digitado e validado pelas regras do `RepoScanner` (P7). Orçamento da varredura derivado do contexto do modelo (§6.6). Uso de qualquer modelo instalado e local, inclusive fora do catálogo; downloads do DocGen continuam restritos ao catálogo (§6.3). |
| 7 | Instaladores (`jpackage` + `jlink`) e workflow em matriz | 6 | — | — |
| 8 | Site estático em `site/` na Vercel | 7 (links dos assets reais) | — | A publicação do app já sai na Fase 1 (P8); aqui o `site/` ganha o conteúdo completo. |
| 9 | Documentação e roteiro E2E manual | 8 | — | — |

## 8. Riscos

| # | Risco | Impacto | Mitigação | Fase |
|---|---|---|---|---|
| R1 | Truncamento silencioso do contexto pelo Ollama (A1) | Alto: regras de segurança e instruções perdidas, saída ruim | `num_ctx` explícito, estimativa de tokens com folga (calibrada para Qwen; outras famílias são melhor esforço), falha fechada quando não couber, `num_ctx` em degraus e corte de histórico com aviso no chat (§6.6) | 1, 4 |
| R2 | O token de lançamento aparece na linha de comando do navegador (`/proc/<pid>/cmdline` é legível por outros usuários do Linux por padrão) até ser trocado | Médio: em máquina compartilhada, outro usuário pode tentar usar o token antes do navegador | Uso único, validade curta, detecção de colisão (token já usado → revoga a sessão criada com ele e avisa) (ADR 0002) | 2 |
| R3 | Um XSS na UI leria o token de sessão no `sessionStorage`. O cookie foi descartado porque cookies não são isolados por porta (RFC 6265, §8.5) | Médio | CSP estrita, nenhum *sink* HTML (com teste que varre o código), Markdown do modelo nunca interpretado como HTML, sessão que morre com o app (ADR 0002) | 2, 4 |
| R4 | A branch do launcher diverge das decisões e já está publicada | Médio: trabalho duplicado | Decidir P1 antes da Fase 2; reaproveitar o que a seção 4.6 lista | 2 |
| R5 | Modelos de nuvem do Ollama (A10) | Alto: violaria o princípio 1 | Defesa em camadas (ADR 0003): catálogo como lista de permitidos para baixar e escolher; rejeitar modelo cuja tag ou cujos metadados indiquem execução remota, verificado na documentação e empiricamente; nenhuma dependência da configuração do Ollama do usuário; onboarding que recomenda o modo só local citando a fonte oficial. **Mitigado na F3** (ADR 0003, implementação) | 3 |
| R6 | Cancelar o `/api/pull` pode não interromper o download no Ollama | Médio | Testar com o Ollama real na F3, sem afirmar nada antes. **Verificado na F3:** fechar a conexão para o download, e o pull seguinte retoma | 3 |
| R7 | O catálogo envelhece rápido (famílias novas a cada poucos meses) e os tamanhos variam por plataforma | Médio | Catálogo versionado com data e `verified`; a UI mostra o tamanho informado pelo Ollama durante o download | 3 |
| R8 | VRAM livre não é exposta pelo OSHI; sem `nvidia-smi` (AMD, Intel, integradas) a VRAM fica desconhecida; Apple Silicon usa memória unificada | Médio | "Não detectado" em vez de inventar; classificar de forma conservadora quando faltar dado; sondas sem shell e com timeout. **F3:** o OSHI 7 lê total e uso pela NVML (NVIDIA) e pelo `amdgpu`; VRAM sem confirmação do driver não entra na conta; Apple Silicon por premissa documentada (não medido) | 3 |
| R9 | A estimativa de memória erra (KV cache depende da arquitetura; modelos de visão têm projetor; o Ollama pode quantizar o KV cache) | Médio | Fórmula documentada com folga; comparar com `size`/`size_vram` do `/api/ps` depois de carregar. **F3:** calibrada com dois modelos (ADR 0004), 0,3 a 0,8 GB acima da medição; a comparação por modelo carregado fica para a F4 | 3, 4 |
| R10 | Empacotamento: cada SO gera o próprio instalador; MSI do JDK 21 exige WiX 3; sem certificado, SmartScreen e Gatekeeper alertam; não há notarização da Apple | Médio | Matriz no GitHub Actions; documentação honesta de como prosseguir; disponibilidade do WiX nos runners a verificar na F7 | 7 |
| R11 | Navegador: o modo `--app` do Chromium e os navegadores em Snap/Flatpak se comportam de jeitos diferentes | Baixo | Tentativa com fallback para o navegador padrão; testar nos três SOs | 2, 7 |
| R12 | O Spring Boot leva alguns segundos para subir sem nenhuma resposta visual, e o usuário clica de novo | Baixo | Instância única antes do Spring; splash nativo da JVM (`-splash`) avaliado na F2 | 2 |
| R13 | Reescrever a autenticação dos testes pode enfraquecer a cobertura de segurança | Médio | Mapear teste antigo para teste novo no relatório da F2; nenhum teste de segurança sai sem substituto | 2 |
| R14 | Dados locais ficam sem criptografia em repouso (H2 em arquivo) | Baixo | Diretório `0700`; documentar que a criptografia de disco é do SO | 2, 9 |
| R15 | Ambiente de desenvolvimento sem JDK 21 (só JBR 25 e OpenJDK 27) | Baixo | Build com `--release 21`; a CI com Temurin 21 continua sendo a referência | todas |
| R16 | Com a UI no navegador, fechar a aba não encerra o processo, e o usuário pode não perceber que o app segue rodando | Médio | Heartbeat da UI e desligamento após 10 min sem sinal, nunca com download ou geração em andamento (P2); ícone de bandeja quando houver suporte | 2 |
| R17 | Modelos com modo de raciocínio (Qwen3+) misturam o raciocínio à resposta | Médio: documentação poluída ou reprovada pelo validador | Tratamento explícito do raciocínio antes do `DocumentationValidator`, do parser e da tela (§6.4). **Feito na F3** | 3 |

## 9. Decisões tomadas na aprovação da Fase 0 (2026-10-24)

| # | Tema | Decisão |
|---|---|---|
| P1 | Branch do launcher | Seguir 4.1/4.2 a partir de `main`. Aproveitar só a trava de instância, os diretórios privados, a abertura do navegador e os scripts `jlink`. Não alterar `feat/docgen-desktop-launcher`. |
| P2 | Ciclo de vida | Heartbeat da UI; desligar após 10 min sem heartbeat, **nunca** com download ou geração em andamento. Fase 2. |
| P3 | Windows | `%LOCALAPPDATA%\DocGen`. |
| P4 | Anexos | Guardar só o texto extraído, mais nome, tamanho e SHA-256 do original. |
| P5 | Contexto | Na Fase 1, em commit separado: `num_ctx` explícito, dimensionado pelo tamanho estimado do prompt, com teto no contexto máximo do modelo; falha fechada com erro claro quando não couber; teste de regressão que reproduz o truncamento medido. |
| P6 | Modelo padrão da CLI | `qwen2.5-coder:7b`. Se não estiver instalado, a CLI falha com mensagem amigável e não baixa sozinha. Requisito registrado: modelos com modo de raciocínio (Qwen3+) exigem tratamento do raciocínio antes do `DocumentationValidator` (§6.4). |
| P7 | Repositórios no app | Fase 6. O navegador não fornece o caminho real da pasta: diálogo nativo aberto pelo processo Java, ou caminho digitado e validado pelas mesmas regras do `RepoScanner`, revogável pela UI. |
| P8 | Vercel | Fase 1: `vercel.json` aponta para `site/` com uma página estática mínima ("DocGen Desktop em desenvolvimento" + link do repositório), sem o frontend do app. |
| P9 | Fluxo de branches | Um PR por fase para `main`; o revisor faz o merge e a fase seguinte parte de `main` atualizado. Nada de push sem autorização: ao fim de cada fase, aviso que a branch está pronta para PR. |

Revisões aprovadas junto com a Fase 0:

- **ADR 0002:** o cookie foi substituído por um token de sessão Bearer guardado só no `sessionStorage` e
  enviado no cabeçalho `Authorization`, porque cookies não são isolados por porta. As validações de `Host` e
  de `Origin`/`Sec-Fetch-Site` ficam como defesa em profundidade, e o streaming do chat usa `fetch` +
  `ReadableStream`.
- **ADR 0003:** defesa em camadas contra modelos de nuvem do Ollama (lista de permitidos pelo catálogo;
  rejeição por tag ou metadados; verificação documental e empírica do `/api/show`; nenhuma dependência da
  configuração local do Ollama; onboarding com o modo só local e a fonte oficial).
- **Catálogo:** as famílias novas entram só com tags verificadas; tamanho em faixa usa o limite superior na
  estimativa de memória.

Respostas registradas na aprovação da Fase 1:

- **Sessão:** 12 h de inatividade, sessões somente em memória; reiniciar o app invalida todas (ADR 0002).
- **`V3`:** dono fixo `local`, mantendo a coluna `owner` com esse valor constante em vez de removê-la.
- **Orçamento da varredura:** proposta para a Fase 6 (§6.6).
- **Janela de contexto:** chat com `num_ctx` estável e em degraus na Fase 4; estimador calibrado para Qwen, as
  demais famílias como melhor esforço; correção do `provider_unavailable` na Fase 3 (§6.6).
- **Fluxo de merge:** um PR por fase, com merge commit e sem squash, depois da CI verde com Temurin 21. A Fase 0
  entra primeiro.

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

## 11. Fase 2: resultado (2026-10-24, aprovada e integrada em `main` pelo PR #23)

Branch: `ccr-97d33570-sr09oz` (a sessão em nuvem desta fase exige essa branch), criada a partir de `main` (`e8cb37f`).

### Linha de base e resultado

Ambiente: Linux x86_64 em contêiner, OpenJDK **21.0.12** (agora com JDK 21 de verdade, como a CI), Maven 3.9.11, Node.js 22.22.

| Medição | `main` (`e8cb37f`) | Fase 2 |
|---|---:|---:|
| docgen-core | 189 | 189 |
| docgen-cli | 16 | 16 |
| docgen-api (inclui o pacote desktop) | 56 | 120 |
| **Total Java** (`mvn -B clean verify`) | **261**, `BUILD SUCCESS`, ~50 s | **325**, `BUILD SUCCESS`, ~39 s |
| Frontend (`node --test`) | 49 | 60 |

Observação de ambiente: sem locale UTF-8 (`LANG` vazio, `sun.jnu.encoding` ASCII), três testes do `RepoScannerTest` falham ao criar arquivos com nomes Unicode. É do ambiente, não do código (passam com `LANG=C.UTF-8`, como na CI). Antes desta fase já era assim.

### Mapeamento dos testes de autenticação (R13)

Nenhum teste de segurança saiu sem substituto.

| Antes | Depois |
|---|---|
| `issuesBearerTokensThatAuthenticateAndCanBeRevoked` | `aLaunchTokenBecomesASessionThatAuthenticatesAndCanBeEnded` |
| `rejectsWrongCredentialsWithoutRevealingWhichPartWasWrong` | `unknownOrMalformedLaunchTokensAreRefusedWithoutDetail` |
| `throttlesRepeatedFailedSignIns`, `basicAuthenticationSharesTheLockout` | Sem senha não há força bruta de login: `aLaunchTokenWorksOnceAndItsReuseEndsTheSessionItCreated` e `LaunchTokenServiceTest` (256 bits, uso único, 30 s, reuso detectado) |
| `forgedOrMissingTokensAreRejectedAndTheWebClientGetsABearerChallenge` | `forgedOrMissingTokensAreRejectedWithABearerChallenge` e `ProtectedRoutesTest` (todas as rotas mapeadas) |
| `basicAuthenticationStillWorksForScripts` | Invertido: `basicAuthenticationIsNotAcceptedAnyMore` |
| `rejectsForeignHostHeadersToBlockDnsRebinding` | `rejectsForeignHostsAndPortsToBlockDnsRebinding` (mais estrito: exige a porta) |
| `servesTheWorkspaceWithAStrictContentSecurityPolicy` | `servesTheWorkspaceAndTheLaunchPageWithAStrictContentSecurityPolicy` |
| `theRemoteProviderCatalogNoLongerExists` | `theRemovedRoutesNoLongerExist` |
| `projectsAreIsolatedPerUser`, `attachmentsCanBeDeletedOnlyByTheirOwner`, `limitsActiveJobsPerUserAndHidesJobsFromOtherUsers` | Com linhas de outro dono gravadas direto no banco: `rowsOfAnotherOwnerStayInvisibleToTheLocalSession`, `attachmentsOfAnotherOwnerCannotBeReachedOrDeleted`, `limitsActiveJobsAndHidesJobsOfAnotherOwner` |
| `analysisRequiresValidBasicAuthentication`, `analysisRequiresTheAnalystRole` | `analysisRequiresASession`, `analysisRequiresTheLocalSessionRole` |
| JS: `login stores the token…` | `a launch token becomes the session…` |
| Mantidos | Content-Type obrigatório, CORS, rotas negadas sem vazamento |

### Desvios em relação ao plano

| Item do plano | O que foi feito | Por quê |
|---|---|---|
| Tokens de lançamento no pacote `com.docgen.desktop` | Em `com.docgen.api.LaunchTokenService`; o launcher implementa `DesktopControl` | Dependência de mão única (desktop → API): a API continua testável sem o launcher. |
| Hashes consumidos "até expirar" | Guardados por 5 min | Detecta o reuso mesmo quando o navegador legítimo demora mais que 30 s (ADR 0002). |
| `install-web.sh` perde a exigência de variáveis na F2 | Mantido como está | Ele instala a última release **publicada** (v2.0.0), que ainda exige usuário e senha. Mudá-lo antes de uma release 3.0 quebraria a única versão instalável. Sai na Fase 7. |
| `DELETE /data` | Implementado, com confirmação digitada na UI e compactação do H2 na saída | Linhas apagadas podem ficar em páginas livres do arquivo (ADR 0001). |
| Splash nativo (R12) | Não implementado | Depende do empacotamento; fica para a Fase 7. |

### Pendências e riscos desta fase

- Bandeja, `Desktop#browse`, Chromium `--app` e diálogos AWT não foram verificados num desktop real (ambiente *headless*); Windows e macOS também não. Fica para a validação da Fase 7.
- R2 (token na linha de comando) continua como risco residual declarado, agora com detecção de reuso de 5 min e a recomendação `hidepid=2`.
- O `CHANGELOG.md` ainda diz que a 2.0.0 "não foi publicada", mas a release v2.0.0 existe no GitHub desde 2026-10-22. Inconsistência anterior a esta fase, não corrigida aqui.

## 12. Fase 3: resultado (2026-10-25, aguardando aprovação)

Branch: `feat/desktop-fase3-ollama-modelos`, criada a partir de `main` (`848ae35`, com a Fase 2).

### Linha de base e resultado

Ambiente: Arch Linux em sandbox Flatpak, JetBrains Runtime 25.0.4 com `--release 21` (sem JDK 21 local; a CI com
Temurin 21 continua sendo a referência, R15), Maven 3.9.16, Node.js 24.16.0, Ollama 0.32.11, RTX 5070 de 12 GB.

| Medição | `main` (`848ae35`) | Fase 3 |
|---|---:|---:|
| docgen-core | 189 | 233 |
| docgen-cli | 16 | 17 |
| docgen-api (inclui `desktop`, `hardware` e `models`) | 120 | 169 |
| **Total Java** (`mvn -B clean verify`) | **325**, `BUILD SUCCESS`, 27 s | **419**, `BUILD SUCCESS`, 32 s |
| Frontend (`node --test`) | 60 | 80 |

### Verificações feitas antes de implementar

| Item | Fonte | Resultado |
|---|---|---|
| Formatos de `/api/chat`, `/api/pull`, `/api/ps`, `/api/show`, `/api/delete`, `/api/version` | `docs/api.md`, docs.ollama.com/api, `api/types.go` e chamadas reais ao 0.32.11 | O pull manda erros **dentro do stream com HTTP 200**; `think: true` em modelo sem raciocínio dá 400; `/api/ps` traz `context_length`. Fixtures em `docgen-core/src/test/resources/ollama`. |
| Modelos de nuvem | `api/types.go`, `server/create.go`, docs.ollama.com/cloud e teste empírico | `remote_host`/`remote_model` em tags, show e chat; tag `cloud` (ADR 0003). |
| Cancelamento do pull (R6) | `docs/api.md` ("cancelled pulls are resumed") e teste real com `qwen3:0.6b` | Fechar a conexão para o download; o seguinte retoma. |
| Host do download | `types/model/name.go` | `registry.ollama.ai`, namespace `library`. |
| Tags Qwen | Páginas da biblioteca e `config.json` oficiais | 16 tags verificadas (ADR 0004). |
| Raciocínio | docs.ollama.com/capabilities/thinking e cartões da Qwen | `think` booleano; as `instruct-2507` não raciocinam. |
| OSHI | Maven Central e código-fonte 7.7.1 | MIT; JNA LGPL-2.1 ou Apache-2.0; VRAM pela NVML e pelo `amdgpu`; sem rede. |
| Fração da GPU no Apple Silicon | Busca: só relatos de usuários (~75%) | **Não verificado**; premissa de dois terços registrada. |

### Verificação ponta a ponta (JAR real, Ollama 0.32.11, hardware real)

Com um `xdg-open` substituto que só registra o link e o Chromium headless do Playwright dirigido pelo protocolo
DevTools: o onboarding detectou o Ollama, mostrou o hardware (RTX 5070 com 12 GB e 11 GB livres, medidos pelo
driver; a pasta de modelos do Ollama não é visível de dentro do sandbox, e a tela disse isso), sugeriu
`qwen3.5:9b` e `qwen2.5-coder:7b`, retomou e concluiu o download parcial de `qwen3:0.6b`, testou o modelo e o
definiu como padrão. Nenhum erro de console, nenhuma violação de CSP e nenhuma requisição fora de `127.0.0.1`.
Pela API, um modelo remoto criado com o nome `qwen3:1.7b` foi recusado (`422 remote_model_refused`); tags fora do
catálogo e `cloud` também (`422 not_in_catalog`); sem sessão, `401`. Depois, `qwen3:0.6b` e o modelo remoto foram
removidos pelo próprio DocGen, e o Ollama voltou aos modelos que tinha antes.

Achados desse teste, corrigidos na fase: a velocidade de um download retomado contava o salto do que já estava no
disco (668 MB/s); e os tokens por segundo da primeira resposta logo após o download (3 tokens/s, contra 530 a 590
nas seguintes, pela mesma rota) faziam o modelo parecer lento, então o onboarding passou a mostrar o tempo da
primeira resposta. A causa da primeira resposta lenta não foi investigada.

### Desvios em relação ao plano

| Item do plano | O que foi feito | Por quê |
|---|---|---|
| `GET /models/pulls/{id}/events` (stream) | *Polling* do instantâneo a cada 0,7 s | O progresso vem dos eventos do Ollama de qualquer forma; o *polling* sobrevive a recarregar a aba e não antecipa a decisão SSE × NDJSON da Fase 4. |
| `GET /models/{nome}` e `DELETE /models/{nome}` | Nome na query (`?name=`) | Tags podem ter `/`. |
| Migração só na Fase 4 | `V4` (`app_settings`) nesta fase | O modelo padrão e o fim do onboarding precisam persistir. Chat e anexos passam a `V5` e `V6`. |
| `qwen3:4b` e `qwen3:30b` | Substituídas por `qwen3:4b-instruct` e `qwen3:30b-instruct` | As tags padrão são as versões só de raciocínio. |
| Velocidade em tokens/s por modelo na recomendação | Não há | Nada foi medido por modelo e hardware (ADR 0004). |
| Projetos sem modelo usam `qwen2.5-coder:7b` | Usam o modelo padrão do app, quando há um | O usuário escolhe um modelo no onboarding; a Engenharia passa a usá-lo. |

### Pendências e riscos desta fase

- Sem JDK 21 local: o cancelamento do stream depende de o `close()` do corpo destravar a leitura, o que foi
  conferido no código do JDK 21 e testado no JBR 25; a CI confirma no Temurin 21.
- OSHI no JDK 24+ emite um aviso de acesso nativo da JNA; o pacote da Fase 7 deve passar
  `--enable-native-access=ALL-UNNAMED`. No JDK 21 não há aviso.
- Diagnóstico não verificado em Windows, macOS, GPU AMD e Apple Silicon (Fase 7).
- O campo de modelo dos projetos da Engenharia ainda aceita qualquer nome (a camada 2 cobre); Fase 6.
- `DesktopLauncherTest` tinha uma corrida da Fase 2 (o teste lia o contador de saídas antes da chamada); corrigida
  no teste, sem mudar a verificação.
- O teste de cancelamento real deixou ~478 MB parciais do `qwen3:0.6b` no Ollama, que o teste ponta a ponta concluiu
  e depois removeu.

