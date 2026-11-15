# Desenvolvimento do DocGen

> Detalhes técnicos movidos do [README](../README.md): tecnologias, organização do código, testes, CI e distribuição.

## Tecnologias utilizadas

| Tecnologia | Versão | Responsabilidade |
|---|---|---|
| Java | 21 | Linguagem e runtime. Usa `java.net.http.HttpClient` e `SecureDirectoryStream` (NIO). |
| Maven | 3.9+ | Build, testes e empacotamento. |
| picocli | 4.7.7 | Parsing dos argumentos da CLI, `--help` e `--version`. |
| Jackson Databind | 2.21.8 (core) · 3.1.5 (API) | JSON nos provedores e no plano (core) e na camada HTTP do Spring Boot (API). |
| Spring Boot | 4.1.1 | API REST (Spring MVC 7, Spring Security 7, Bean Validation, JDBC). |
| H2 Database | 2.4.240 | Banco embutido local, em arquivo, num diretório privado. |
| Flyway | 12.4.0 | Migrações versionadas do esquema. |
| Apache PDFBox | 3.0.8 | Extração de texto de PDFs, sem renderização. |
| HTML5, CSS3, ES Modules | — | Workspace web sem framework e sem build, servido pela API. |
| Node.js (`node:test`) | 20+ | Somente para os testes do frontend; nenhuma dependência npm. |
| JUnit Jupiter | 6.1.0 | Testes automatizados. |
| maven-shade-plugin | 3.6.2 | Gera o JAR executável da CLI `target/docgen.jar`, com as dependências incluídas. |
| spring-boot-maven-plugin | 4.1.1 | Gera `docgen-api/target/docgen-api.jar` (API e workspace web). |
| cyclonedx-maven-plugin | 2.9.2 | Gera SBOMs CycloneDX 1.6 por artefato (`target/docgen.cdx.json` e `target/docgen-api.cdx.json`) na fase `verify`. |
| Ollama | — | Provedor de LLM local, o único suportado. |
| GitHub Actions | — | CI, varreduras de segurança e pipeline de release. |
| CodeQL | — | Análise estática de Java e JavaScript com a suíte `security-extended`. |
| OSV-Scanner | — | Bloqueio de dependências com vulnerabilidades conhecidas. |
| Dependency Review | — | Revisão das mudanças de dependências em pull requests. |
| Dependabot | — | Atualização de dependências e das actions fixadas por SHA. |
| Sigstore / Cosign | — | Assinatura keyless dos artefatos de release e sua verificação. |

## Arquitetura do projeto

O DocGen é um Maven reactor com quatro módulos. As interfaces ficam separadas de um **motor reutilizável** (pacote `com.docgen`), que não depende de HTTP, Servlet, HTML ou terminal:

| Módulo | Conteúdo | Artefato |
|---|---|---|
| `docgen-core` | Varredura segura, filtros, redação, prompts, provedores, validação, escrita transacional, planejamento arquitetural e diagramas. | biblioteca |
| `docgen-cli` | Interface Picocli (parsing, terminal, mensagens e códigos de saída). | `target/docgen.jar` |
| `docgen-web` | Workspace web (HTML, CSS, ES Modules), sem framework e sem build. | recursos `static/` |
| `docgen-api` | Spring Boot: REST, autenticação, autorização, uploads, jobs, persistência H2 e Flyway; serve o `docgen-web`. | `docgen-api/target/docgen-api.jar` |

O pipeline da CLI continua o mesmo:

```text
DocGenCLI
  -> DocumentationRequest
  -> DocumentationService
      -> RepoScanner (+ DocgenIgnore, SecretRedactor)
      -> [--dry-run encerra aqui]
      -> preflight seguro de saída/pré-visualização
      -> PromptBuilder
      -> [--preview-prompt encerra aqui, antes de qualquer provedor existir]
      -> LLMProviderFactory
          -> OllamaProvider (num_ctx explícito, 127.0.0.1)
      -> DocumentationValidator
      -> OutputWriter (revalida antes da publicação)
  -> DocumentationResult
```

| Responsabilidade | Classes |
|---|---|
| Interface de terminal (`docgen-cli`) | `DocGenCLI` |
| Contratos e orquestração reutilizável | `DocumentationRequest`, `DocumentationResult`, `DocumentationService` |
| Leitura do repositório | `RepoScanner`, `RepoContext` |
| Filtros e segurança | `DocgenIgnore`, `SecretRedactor`, `SafeText` |
| Construção dos prompts | `PromptBuilder` |
| Integração com o Ollama | `LLMProvider`, `LLMProviderFactory`, `OllamaProvider`, `OllamaEndpoint`, `PromptTokens`, `HttpExchange`, `DirectProxySelector`, `BoundedUtf8BodySubscriber` |
| Validação | `DocumentationValidator` |
| Persistência | `OutputWriter`, `PromptPreviewWriter`, `SecurePairWriter` |
| Planejamento arquitetural | `ProjectBrief`, `SupportingDocument`, `ArchitecturePlan`, `ArchitecturePlanParser`, `ArchitecturePlanJson`, `ArchitecturePlanningService`, `ProjectDocumentationService`, `ProjectPromptBuilder`, `MermaidDiagram`, `UntrustedText` |
| API e workspace (`docgen-api`, `docgen-web`) | controllers, serviços, repositórios JDBC, jobs, segurança e as views do frontend; veja [ARCHITECTURE.md](../ARCHITECTURE.md#docgen-api) |

O `LLMProvider` abstrai o modelo local; a única implementação é o `OllamaProvider`. O `DocumentationResult` não expõe caminhos absolutos nem o conteúdo-fonte bruto, enquanto o `RepoContext` interno separa exclusões visíveis no prompt das exclusões apenas locais.

A descrição detalhada de cada componente e das fronteiras de segurança está em [ARCHITECTURE.md](../ARCHITECTURE.md).

## Testes e qualidade

### Estratégia de testes

A suíte usa JUnit Jupiter (Java) e `node:test` (frontend), sem chamadas reais ao Ollama: os testes da API substituem o provedor por um duplo programável, e os do core usam um Ollama falso em loopback. Contagem atual: **189** testes no core, **16** na CLI, **56** na API e **49** no frontend.

| Classe de teste | Foco |
|---|---|
| `RepoScannerTest` | Varredura, exclusões, regras de ignore e limites. |
| `OutputWriterTest` | Escrita segura, sobrescrita e preflight. |
| `SecretRedactorTest` | Redação de segredos. |
| `DocGenCLITest` | Fluxo da CLI, recusa de outros provedores, porta inválida, modelo ausente e prompt grande demais. |
| `DocumentationServiceTest` | Orquestração, contratos, modos sem provedor, validação e ausência de publicação parcial. |
| `PromptBuilderTest` | Construção e isolamento dos prompts. |
| `PromptPreviewWriterTest` | Gravação das pré-visualizações. |
| `ProviderResponseValidationTest` | Validação das respostas do Ollama. |
| `OllamaContextWindowTest`, `PromptTokensTest`, `OllamaEndpointTest` | Regressão do truncamento de contexto medido (servidor falso), calibração do estimador de tokens contra contagens reais e endpoint fixo em loopback. |
| `DocumentationValidatorTest` | Contrato de Markdown passivo. |
| `SafeTextTest` | Utilitários de texto seguro. |
| `ArchitecturePlanParserTest`, `MermaidDiagramTest`, `ProjectPromptBuilderTest`, `UntrustedTextTest` | Validação do plano, separação entre requisitos do usuário e sugestões da IA, Mermaid restrito, isolamento de prompts e normalização de texto. |
| `ArchitecturePlanningServiceTest`, `ProjectDocumentationServiceTest`, `ProviderJsonModeTest` | Planejamento, escolha do modelo, falhas de provedor, Markdown inseguro, modo JSON e `num_ctx`. |
| `AuthAndSecurityTest` | Tokens, Basic, bloqueio progressivo, Content-Type obrigatório (CSRF), CORS, Host header, CSP e rotas negadas. |
| `ProjectApiTest`, `AttachmentApiTest` | CRUD, validação estrita, isolamento entre usuários e upload de PDF (válido, falso, criptografado, sem texto, grande demais, duplicado). |
| `WorkflowApiTest`, `JobTimeoutTest` | Fluxo ponta a ponta (PDF → plano → aprovação → documentação → edição → exportação), ausência de envio remoto, falhas saneadas (incluindo contexto e modelo ausente), cancelamento, limites e timeout. |
| `LocalOnlyArchitectureTest`, `MigrationTest`, `OllamaPropertiesTest` | Regra de arquitetura "somente local" sobre o código de todos os módulos, migração V2 e porta do Ollama. |
| `DocGenApiApplicationTest`, `AuthorizedRepositoryRegistryTest` | Endpoints da Fase 02 e registro de repositórios autorizados. |
| `docgen-web/src/test/js/*.test.mjs` | DOM seguro, allowlist de URLs, Markdown, layout do diagrama, cliente HTTP, polling, validação, rotas, varredura estática de sinks proibidos e de URLs externas, e a configuração da Vercel. |

```bash
mvn -B test                                        # testes Java de todos os módulos
mvn -B verify                                      # testes + SBOMs CycloneDX
node --test docgen-web/src/test/js/*.test.mjs      # testes do frontend (Node 20+)
```

### Integração contínua (`ci.yml`)

| Job | Quando | O que faz |
|---|---|---|
| `test-and-sbom` | push em `main`, PRs, manual | `mvn -B verify` com Temurin 21, testes do frontend com `node --test` e publicação dos SBOMs como artefato (retenção de 14 dias). |
| `dependency-review` | pull requests | Rejeita dependências com vulnerabilidades de severidade moderada ou superior. |
| `dependency-scan` | push, PRs, semanal, manual | OSV-Scanner completo. Uma vulnerabilidade faz o job falhar, e o resultado é enviado em SARIF. |
| `codeql` | push, PRs, manual | CodeQL para Java com a suíte `security-extended`. |
| `codeql-javascript` | push, PRs, manual | CodeQL para o JavaScript do workspace, com a suíte `security-extended`. |

Práticas adotadas nos workflows:

- permissões padrão nulas (`permissions: {}`), concedidas job a job;
- actions e workflows reutilizáveis de terceiros fixados por **SHA completo de commit** e mantidos atualizados pelo Dependabot;
- checkouts sem persistência de credenciais (`persist-credentials: false`).

> [!NOTE]
> Para que essas verificações bloqueiem merges, é preciso configurar branch protection ou rulesets que as exijam.

## Distribuição e integridade

O workflow `release.yml` é executado ao publicar uma GitHub Release e está restrito a `brunocorreia-dev/Docgen`:

1. **`dependency-scan`**: OSV-Scanner sobre o commit imutável da release.
2. **`build-artifacts`**: depende de uma varredura limpa. Executa `mvn -B verify` e gera os checksums SHA-256, com permissão apenas de `contents: read`.
3. **`sign-and-upload`**: job separado, sem checkout nem execução do Maven, que recebe somente os artefatos já construídos. Assina cada artefato e cada checksum com **cosign keyless** via GitHub Actions OIDC e os envia para a release.

Antes dos checksums, o job de build executa `java -jar target/docgen.jar --version` e confirma que o `docgen-api.jar` contém o frontend (`docgen-web`).

**Artefatos publicados:**

| Arquivo | Descrição |
|---|---|
| `docgen.jar` | JAR executável da CLI (nome inalterado). |
| `docgen.jar.sha256` | Checksum SHA-256 do JAR. |
| `docgen.cdx.json` | SBOM CycloneDX 1.6 da CLI. |
| `docgen.cdx.json.sha256` | Checksum do SBOM. |
| `docgen-api.jar` | JAR Spring Boot da API REST com o workspace web embutido (v2.0.0+). |
| `docgen-api.jar.sha256` | Checksum do JAR da API. |
| `docgen-api.cdx.json` | SBOM CycloneDX 1.6 da API. |
| `docgen-api.cdx.json.sha256` | Checksum do SBOM da API. |
| `*.sigstore.json` | Bundle Sigstore de cada um dos arquivos acima. |

### Verificação manual de uma release

Como a assinatura é keyless, não existe chave pública de longa duração: a identidade de assinatura é o próprio workflow de release. Baixe `docgen.jar` e `docgen.jar.sigstore.json` da release e execute:

```bash
cosign verify-blob \
  --bundle docgen.jar.sigstore.json \
  --certificate-identity-regexp '^https://github\.com/brunocorreia-dev/Docgen/\.github/workflows/release\.yml@refs/tags/' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  docgen.jar
```

A verificação bem-sucedida comprova que o JAR foi assinado pela identidade `release.yml` deste repositório em uma tag de release. A proveniência do build depende também do workflow revisado, que assina apenas os artefatos recebidos do job de build somente leitura da mesma execução. O mesmo comando funciona para `docgen.jar.sha256`, `docgen.cdx.json` e o respectivo checksum, cada um com seu bundle `.sigstore.json`.

> [!NOTE]
> Sem o cosign, o checksum detecta corrupção, mas não a substituição maliciosa conjunta do artefato e do checksum. As assinaturas não protegem contra o comprometimento do repositório ou da definição do workflow.

### Situação das releases

A última release publicada é a **v2.0.0** (2026-10-22), com `docgen.jar`, `docgen-api.jar`, os SBOMs, os checksums e os bundles Sigstore de cada um. Ela é anterior à 3.0: ainda traz o provedor remoto e o login do workspace. A versão 3.0 (`3.0.0-SNAPSHOT`, somente local) ainda não tem release; use o build pelo código-fonte. Os instaladores do app desktop (`.deb`, `.rpm`, `.msi`, `.dmg`) estão planejados para a Fase 7.

## Histórico do projeto

Histórico recente:

1. **Fase 01**: extração do `DocumentationService` reutilizável.
2. **Fase 02**: Maven reactor (`docgen-core`, `docgen-cli`, `docgen-api`) e primeira API REST.
3. **DocGen Web**: workspace web, projetos, PDFs, planejamento arquitetural estruturado, diagramas, jobs, documentação revisável e persistência local.
4. **3.0.0 (em desenvolvimento)**: realinhamento como app desktop de IA local. Implementado: somente Ollama, sem provedor remoto, com janela de contexto explícita e modelo padrão `qwen2.5-coder:7b`. Planejado: launcher desktop sem login, chat com streaming, gerenciador de modelos Qwen, anexos no chat e instaladores.

O andamento atual está no [roadmap](desktop/ROADMAP.md).

## Ideias registradas antes do realinhamento

Próximos passos considerados antes do [roadmap atual](desktop/ROADMAP.md). O que já foi feito ou entrou numa fase está indicado:

- publicar e assinar o JAR da API nas releases, com SBOM próprio (feito na v2.0.0);
- OCR opcional para PDFs digitalizados, em processo isolado (fora do escopo atual; registrado como trabalho futuro no roadmap);
- documentação de repositórios autorizados pelo workspace (hoje a API só faz a análise sem IA; planejado para a Fase 6);
- edição estruturada do plano antes da aprovação e comparação entre versões;
- notificações por SSE quando os jobs ficarem mais longos;
- testes de navegador automatizados na CI (hoje executados localmente com Chromium headless).

