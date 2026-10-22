# Changelog

Todas as mudanças relevantes deste projeto. O formato segue [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e o versionamento segue [SemVer](https://semver.org/lang/pt-BR/).

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
