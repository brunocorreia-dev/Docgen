# DocGen v2.0.0

## Resumo

O DocGen deixa de ser apenas uma CLI que documenta repositórios existentes. Agora ele também inclui o **DocGen Web**, uma API REST com workspace web local que transforma uma ideia de projeto (descrição, requisitos e PDFs de especificação) em um plano arquitetural estruturado, revisado por uma pessoa, e depois em `README.md` e `ARCHITECTURE.md`. A CLI continua disponível sem mudanças de uso.

## Novidades

- Projetos com nome, descrição, requisitos funcionais e não funcionais, tecnologias preferidas, restrições, provedor, modelo e idioma (pt-BR ou inglês).
- Anexos PDF: extração de texto com PDFBox, redação de segredos, limites de tamanho, páginas e caracteres, e pré-visualização do trecho extraído.
- Planejamento arquitetural com IA: o plano JSON é validado por parser estrito, e a origem de cada requisito é identificada (usuário, inferência de documento ou sugestão da IA).
- Aprovação humana obrigatória antes de gerar a documentação.
- Diagrama da arquitetura gerado a partir do plano: exportação Mermaid e visualização SVG no navegador, sem executar Mermaid.
- Geração, edição (com validação do Markdown passivo no servidor) e exportação ZIP de `README.md`, `ARCHITECTURE.md`, diagrama e plano.
- Jobs assíncronos com estados `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` e `CANCELLED`, cancelamento e timeout.

## Melhorias arquiteturais

- Reactor Maven com os módulos `docgen-core` (lógica compartilhada), `docgen-cli`, `docgen-web` (frontend estático) e `docgen-api` (Spring Boot 4.1.1).
- Extração do `ProjectDocumentationService`, reutilizado pela CLI e pela API.
- Persistência local em H2 com migrações Flyway versionadas e diretório de dados privado (`0700`).
- Modo JSON estruturado nos provedores Groq e Ollama.

## Mudanças na API

Todos os endpoints ficam em `/api/v1`. Entre eles:

- `POST /auth/token`, `GET /auth/session` e `DELETE /auth/token`;
- CRUD de projetos;
- upload de anexos (`application/pdf` com o cabeçalho `X-Attachment-Name`);
- `transmission-previews` (pré-visualização e consentimento);
- `plans` e a aprovação de planos;
- `documentation`;
- artefatos, com edição e exportação;
- jobs, com `POST /jobs/{id}/cancellation`;
- `GET /providers`, que agora informa `defaultModel`, `enabled`, `configured` e `requiresConsent`.

Requisições de navegador precisam do cabeçalho `X-DocGen-Client` e de um `Content-Type` não simples. Não há CORS. A referência completa está no README, seção "API REST".

## Interface gráfica

Interface em tema escuro, feita com HTML, CSS e módulos ES, sem framework e sem dependências npm em tempo de execução. Telas incluídas:

- login;
- dashboard;
- lista de projetos;
- novo projeto (com exemplo de demonstração identificado como tal);
- workspace com abas Resumo, Arquitetura, Diagrama, Plano e Documentos;
- histórico de jobs;
- configurações.

O layout é responsivo e funciona em celulares. O frontend vem embutido no `docgen-api.jar` e é servido na mesma origem da API, com CSP `default-src 'none'`.

## Requisitos

- Java 21 ou superior.
- Opcional: Ollama local (`127.0.0.1:11434`) ou uma chave do Groq, que fica só no servidor e exige `DOCGEN_ALLOW_REMOTE=true`.
- Opcional, recomendado: `cosign`, para verificar as assinaturas.

## Instalação

```bash
# CLI (inalterada)
curl -fsSL https://raw.githubusercontent.com/brunocorreia-dev/Docgen/main/install.sh | sh

# DocGen Web
curl -fsSL https://raw.githubusercontent.com/brunocorreia-dev/Docgen/main/install-web.sh | sh
export DOCGEN_API_USERNAME="$USER"
read -rs DOCGEN_API_PASSWORD; export DOCGEN_API_PASSWORD
docgen-web   # http://127.0.0.1:8080
```

No Arch Linux, instale antes com `sudo pacman -S --needed jre21-openjdk curl cosign`. O README tem o passo a passo, inclusive o build pelo código-fonte.

### Artefatos

`docgen.jar`, `docgen.cdx.json`, `docgen-api.jar` e `docgen-api.cdx.json`. Cada um vem com um `.sha256` e com bundles `.sigstore.json` para o arquivo e para o checksum.

## Compatibilidade

- `docgen.jar`, o wrapper `docgen`, as opções da CLI e o `install.sh` mantêm nomes e comportamento. `docgen --version` agora informa `docgen 2.0.0`.
- O `install.sh` continua baixando somente `docgen.jar`. O DocGen Web usa um instalador separado.
- A versão principal subiu por causa da nova superfície (API e web) e da reorganização em módulos Maven, não por quebra da CLI.

## Limitações conhecidas

- O DocGen Web é **somente local**: escuta em `127.0.0.1`, sem HTTPS e sem suporte a exposição em rede.
- Sessões, consentimentos e o controle de tentativas ficam em memória, então reiniciar o servidor encerra as sessões.
- PDFs: sem OCR, sem arquivos protegidos por senha, até 10 MB e 200 páginas por arquivo e 5 anexos por projeto.
- Planos e documentos são propostas da IA. O parser garante estrutura e limites, não a qualidade, e modelos pequenos podem produzir planos rasos.
- A redação de segredos é best-effort.
- Os testes automatizados usam provedores falsos. A integração real com Ollama foi validada manualmente, mas a integração com o Groq não foi exercitada de ponta a ponta nesta versão.
