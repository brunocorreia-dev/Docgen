# Changelog

Todas as mudanças relevantes deste projeto. O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e o versionamento segue [SemVer](https://semver.org/lang/pt-BR/).

## [3.0.0] - em desenvolvimento

Realinhamento do DocGen como aplicativo desktop de IA local. Fase 1: somente local. Fase 2: launcher desktop e autenticação sem login. Fase 3: Ollama, hardware e recomendação de modelos.

### Removido (quebra de compatibilidade)

- Provedor remoto Groq e toda a fronteira remota: `GroqProvider`, `RemoteGate`, `PromptDigest`, `RemoteConsentService`, a configuração `docgen.remote.*` (`DOCGEN_ALLOW_REMOTE`) e a variável `GROQ_API_KEY`.
- Opções `--allow-remote` e `-y`/`--yes` da CLI. `--provider` continua aceito, mas só com `ollama`.
- Endpoints `GET /api/v1/providers` e `POST /api/v1/projects/{id}/transmission-previews`, além do campo `consentToken` nos pedidos de plano e de documentação.
- Diálogo de consentimento e escolha de provedor no workspace web.
- **Login por usuário e senha** (Fase 2): `DOCGEN_API_USERNAME`, `DOCGEN_API_PASSWORD`, `docgen.api.additional-users`, `docgen.api.allowed-hosts`, a tela de login, os endpoints `POST/GET/DELETE /api/v1/auth/*`, o HTTP Basic para scripts e o bloqueio progressivo de tentativas. Scripts locais deixam de acessar a API, de propósito; para automação, use a CLI.
- `DOCGEN_API_PORT` e a porta fixa `8080`: o servidor usa uma porta livre escolhida a cada início.

### Alterado

- O Ollama local é o único provedor. O host é fixo em `127.0.0.1` e só a porta é configurável (`--ollama-port` na CLI, `docgen.ollama.port`/`DOCGEN_OLLAMA_PORT` na API; padrão `11434`). O cliente não usa proxy e não segue redirecionamentos.
- O modelo padrão passa a ser `qwen2.5-coder:7b`. Sem o modelo instalado, a operação falha com mensagem clara e nada é baixado.
- A migração `V2` move para o Ollama os projetos de provedores removidos. Planos e jobs mantêm o provedor histórico.
- A Vercel passa a publicar só `site/`, uma página estática mínima, e não mais o frontend do app.
- `java -jar docgen-api.jar` passa a abrir o app desktop, sem nenhuma variável de ambiente (classe inicial `com.docgen.desktop.DesktopLauncher`). O `docgen-server` só inicia o app, para desenvolvimento.
- O diretório de dados passa a ser por sistema: `$XDG_DATA_HOME/docgen` ou `~/.local/share/docgen` no Linux (o mesmo de antes), `~/Library/Application Support/DocGen` no macOS e `%LOCALAPPDATA%\DocGen` no Windows. `DOCGEN_DATA_DIR` fica só para desenvolvimento.
- A verificação de `Host` passa a exigir também a porta real (`127.0.0.1:<porta>` ou `localhost:<porta>`); `::1` deixa de ser aceito.
- O dono dos dados passa a ser fixo (`local`); a migração `V3` converte os donos da v2. O filtro por dono continua em todas as consultas.
- Projetos sem modelo passam a usar o modelo padrão escolhido no app (Fase 3), e o `qwen2.5-coder:7b` só quando não há um.
- Modelos que sabem raciocinar (Qwen3 em diante) recebem `think: false` na geração de documentação, e o raciocínio que ainda chegar é descartado antes do validador e do parser do plano.

### Corrigido

- O Ollama fora do ar passa a ser `provider_unavailable` (antes `provider_failed`, que mandava tentar de novo), e a CLI explica como abrir o Ollama ou ajustar `--ollama-port`.
- **Truncamento silencioso do contexto.** Sem `num_ctx`, o Ollama usava a janela padrão (4k tokens abaixo de 24 GiB de VRAM) e descartava o início de prompts longos, onde ficam as regras de segurança. Agora cada requisição envia `num_ctx` dimensionado por uma estimativa conservadora do prompt, com teto no contexto máximo do modelo, e `num_predict` limita a resposta. Prompts que não cabem falham antes do envio (`prompt_too_large`), e respostas com contagem de tokens implausível são descartadas (`prompt_truncated`).

### Adicionado

- **Launcher desktop** (ADR 0001): instância única por trava de arquivo do SO; uma segunda execução só pede uma janela nova à instância aberta (`instance.json` privado + `POST /api/v1/desktop/reopen`, recusado se vier de um navegador); servidor em `127.0.0.1` numa porta livre; janela num Chromium em modo `--app`, no navegador padrão ou pelo comando do sistema, sem shell; diálogo "Copiar link de acesso" quando nenhum navegador abre; ícone de bandeja opcional; log rotacionado em `<dados>/logs`.
- **Acesso sem login** (ADR 0002): token de lançamento de uso único (256 bits, 30 s, só o hash no servidor) no fragmento de `/launch`, trocado por um token de sessão Bearer guardado só no `sessionStorage` (`POST/GET/DELETE /api/v1/session`); reuso do token encerra a sessão criada com ele (`409 launch_token_reused`); sessões só em memória, com 12 h de inatividade e no máximo 8; nenhuma resposta define cookie.
- `OriginPolicyFilter`: `POST`/`PUT`/`PATCH`/`DELETE` com `Origin` de outra origem ou `Sec-Fetch-Site` diferente de `same-origin` recebem `403 origin_rejected`.
- "Sair do DocGen" (`POST /api/v1/desktop/shutdown`), heartbeat da interface (`POST /api/v1/desktop/heartbeat`) e desligamento automático após 10 minutos sem janela aberta, nunca durante uma geração.
- "Apagar todos os meus dados" (`DELETE /api/v1/data`), com confirmação digitada e compactação do banco H2 na saída.
- Testes: launcher real em processo (`DesktopLauncherTest`), trava entre processos, `instance.json`, diretórios por SO, abertura do navegador, `401` em todas as rotas mapeadas sem sessão (`ProtectedRoutesTest`), tokens com relógio controlado, desligamento por inatividade, remoção de dados, migração `V3`, página `/launch` e heartbeat no frontend.
- `LocalOnlyArchitectureTest`, que reprova o build quando surge no código de produção uma URI fora do loopback, outro cliente de rede ou outro provedor, e as verificações equivalentes no frontend.
- Teste de calibração do estimador de tokens com contagens medidas no Ollama, e teste de regressão que reproduz o truncamento medido.
- **Cliente Ollama completo** (Fase 3): versão, modelos instalados, detalhes, modelos na memória, remoção, download com progresso e cancelamento, e chat em streaming com cancelamento, com prazos, limites de bytes e linhas e testes contra respostas reais do Ollama 0.32.11.
- **Recusa de modelos que rodam fora do computador** (ADR 0003): tags `cloud` e modelos com `remote_host`/`remote_model` são recusados antes de qualquer envio (`remote_model_refused`), na CLI, na Engenharia e no app.
- **Diagnóstico de hardware** com OSHI 7.7.1 (MIT), sem rede: SO, CPU, RAM total e disponível, placas de vídeo com VRAM confirmada pelo driver (ou pelo `nvidia-smi` opcional), memória unificada no Apple Silicon e espaço livre na pasta de modelos do Ollama; o que não for detectado aparece como "não detectado".
- **Catálogo Qwen versionado** (`models/qwen-catalog.json`, 16 tags verificadas em 2026-10-25) e **recomendador** puro, com estimativa de memória calibrada e as classes Recomendado, Funciona com offload, Só CPU e Não recomendado, explicadas em português (ADR 0004).
- Endpoints `GET /api/v1/ollama/status`, `GET /api/v1/hardware`, `GET /api/v1/models/catalog`, `GET/DELETE /api/v1/models`, `GET /api/v1/models/details`, `POST /api/v1/models/test`, `POST/GET /api/v1/models/pulls`, `GET/DELETE /api/v1/models/pulls/{id}`, `GET /api/v1/settings`, `PUT /api/v1/settings/default-model` e `PUT /api/v1/settings/onboarding`; migração `V4` com as preferências locais.
- **Configuração inicial guiada** (boas-vindas e privacidade, Ollama, hardware, modelo, download confirmado, teste e conclusão) e **tela Modelos** (instalados, padrão, teste, detalhes, remoção, download com progresso e cancelamento, catálogo classificado).

## [2.0.0] - não publicada

### Adicionado

- **DocGen Web**: API REST (Spring Boot 4.1) e workspace web local para planejamento arquitetural assistido por IA: projetos, anexos PDF (PDFBox), planos estruturados com aprovação humana, diagrama da arquitetura, geração de `README.md`/`ARCHITECTURE.md`, edição e exportação ZIP.
- Jobs assíncronos (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`) com cancelamento e timeout.
- Persistência H2 local com migrações Flyway.
- Autenticação por token Bearer (com controle de tentativas), Basic para scripts, verificação do cabeçalho `Host` e CSP estrita.
- Consentimento explícito e de uso único, vinculado ao conteúdo, para cada envio ao Groq, além do bloqueio do operador `DOCGEN_ALLOW_REMOTE`.
- Novo artefato de release `docgen-api.jar`, com SBOM, checksum e assinatura Sigstore próprios.
- Instalador `install-web.sh` e script `docgen-server`.
- Testes do frontend com `node:test` e CodeQL para JavaScript no CI.

### Alterado

- O projeto passou a ser um reactor Maven com os módulos `docgen-core`, `docgen-cli`, `docgen-web` e `docgen-api`.
- Os provedores ganharam um modo JSON estruturado (`response_format` no Groq e `format: json` no Ollama).

### Compatibilidade

- A CLI, o `docgen.jar` e o `install.sh` mantêm nomes, opções e comportamento.

## [1.1.2] e anteriores

Veja as [releases no GitHub](https://github.com/brunocorreia-dev/Docgen/releases).
