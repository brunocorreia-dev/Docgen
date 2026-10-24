# Changelog

Todas as mudanças relevantes deste projeto. O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e o versionamento segue [SemVer](https://semver.org/lang/pt-BR/).

## [3.0.0] - em desenvolvimento

Primeira etapa do realinhamento do DocGen como aplicativo desktop de IA local (Fase 1: somente local).

### Removido (quebra de compatibilidade)

- Provedor remoto Groq e toda a fronteira remota: `GroqProvider`, `RemoteGate`, `PromptDigest`, `RemoteConsentService`, a configuração `docgen.remote.*` (`DOCGEN_ALLOW_REMOTE`) e a variável `GROQ_API_KEY`.
- Opções `--allow-remote` e `-y`/`--yes` da CLI. `--provider` continua aceito, mas só com `ollama`.
- Endpoints `GET /api/v1/providers` e `POST /api/v1/projects/{id}/transmission-previews`, além do campo `consentToken` nos pedidos de plano e de documentação.
- Diálogo de consentimento e escolha de provedor no workspace web.

### Alterado

- O Ollama local é o único provedor. O host é fixo em `127.0.0.1` e só a porta é configurável (`--ollama-port` na CLI, `docgen.ollama.port`/`DOCGEN_OLLAMA_PORT` na API; padrão `11434`). O cliente não usa proxy e não segue redirecionamentos.
- O modelo padrão passa a ser `qwen2.5-coder:7b`. Sem o modelo instalado, a operação falha com mensagem clara e nada é baixado.
- A migração `V2` move para o Ollama os projetos de provedores removidos. Planos e jobs mantêm o provedor histórico.
- A Vercel passa a publicar só `site/`, uma página estática mínima, e não mais o frontend do app.

### Corrigido

- **Truncamento silencioso do contexto.** Sem `num_ctx`, o Ollama usava a janela padrão (4k tokens abaixo de 24 GiB de VRAM) e descartava o início de prompts longos, onde ficam as regras de segurança. Agora cada requisição envia `num_ctx` dimensionado por uma estimativa conservadora do prompt, com teto no contexto máximo do modelo, e `num_predict` limita a resposta. Prompts que não cabem falham antes do envio (`prompt_too_large`), e respostas com contagem de tokens implausível são descartadas (`prompt_truncated`).

### Adicionado

- `LocalOnlyArchitectureTest`, que reprova o build quando surge no código de produção uma URI fora do loopback, outro cliente de rede ou outro provedor, e as verificações equivalentes no frontend.
- Teste de calibração do estimador de tokens com contagens medidas no Ollama, e teste de regressão que reproduz o truncamento medido.

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
