# Segurança

O DocGen trata repositórios, descrições de projeto, PDFs anexados e respostas de modelos de linguagem como **dados não confiáveis**. Quando uma condição não pode ser verificada com segurança, o sistema falha ou exclui o item (fail-closed). Ele nunca recorre a uma alternativa permissiva.

## Tratamento de dados

### Documentação de repositórios (CLI e API de análise)

O DocGen lê arquivos de texto do repositório e envia trechos selecionados e redigidos ao Ollama local, o único provedor. Em cada uma das duas requisições de geração vão:

- o conteúdo redigido de cada arquivo incluído (limites rígidos de 20.000 caracteres por arquivo e 120.000 no total);
- os caminhos relativos redigidos dos arquivos incluídos (também como árvore limitada);
- nomes e motivos, limitados e redigidos, dos arquivos pulados por limite de conteúdo;
- o nome do modelo e as opções `num_ctx` e `num_predict`.

As requisições vão direto ao loopback IPv4 `http://127.0.0.1` (porta padrão `11434`), com proxies da JVM desativados para esse cliente local sem TLS e sem seguir redirecionamentos. O host não é configurável. Nenhum conteúdo sai do computador. Nomes de arquivos sensíveis, ignorados ou não suportados e snapshots de pré-visualização nunca entram em prompts. O endpoint `POST /api/v1/repositories/{id}/analysis` apenas analisa: não constrói provedor nem envia nada.

### Projetos, PDFs e planejamento (DocGen Web)

Num planejamento arquitetural vão ao provedor: nome, descrição, requisitos, tecnologias e restrições do projeto, mais o texto extraído dos PDFs (redigido, normalizado e limitado a um orçamento de 30.000 caracteres, com o prompt inteiro limitado a 64.000). Na geração de documentação vão os mesmos dados do projeto e o plano aprovado, sem o texto dos anexos. Tudo vai apenas para o Ollama local.

## Controles do pipeline de repositórios, na ordem em que se aplicam

1. **Confinamento no sistema de arquivos:** o repositório e cada ancestral, arquivo e entrada de ignore são abertos por handles `SecureDirectoryStream` com `NOFOLLOW_LINKS`, sem nenhuma alternativa baseada em caminho. Sistemas de arquivos sem esse recurso são rejeitados.
2. **Descoberta limitada:** no máximo 4.096 entradas, 512 arquivos tentados, 64 níveis e 512 caracteres de caminho visíveis no prompt, com orçamentos separados para conteúdo, árvore e metadados.
3. **Exclusões de diretórios, pré-visualizações e caminhos sensíveis:** `.git`, saídas de build, pastas de agentes, `docgen-*.prompt.txt`, `.env*`, keystores, chaves privadas, `credentials*`, `*secret*`, contas de serviço e lock files.
4. **`.gitignore` (raiz e aninhados) e `.docgenignore` (raiz):**
   - regras inseguras, com capitalização ou caracteres de controle ambíguos, não NFC, não suportadas ou grandes demais fazem a varredura falhar;
   - os estados sensível e insensível a maiúsculas (ASCII) são combinados de forma conservadora;
   - negações de diretório não anulam exclusões mais específicas de filhos;
   - caminhos não NFC ou com decodificação ambígua são excluídos;
   - o parsing é limitado a 64 KiB por arquivo, 512 regras ordenadas, 2.048 globs determinísticos, 8.192 matchers de segmento, 250.000 estados NFA e um orçamento global de trabalho;
   - negação em `.docgenignore` é rejeitada.
5. **Allowlist opcional** `--include-ext`, que só restringe a seleção.
6. **Redação de segredos** sobre o arquivo completo e limitado antes do truncamento, e sobre cada caminho e item de pulo visível no prompt.
7. **Preflight:** depois da varredura (exceto em `--dry-run`), o par de documentação ou de pré-visualização é verificado antes de construir prompts ou inicializar provedores. Diretório ausente ou inseguro e conflito de sobrescrita falham aqui, sem chamada ao modelo.
8. **Fronteira do prompt:** o conteúdo vai embrulhado como dado não confiável, tags que imitam delimitadores são neutralizadas e as instruções são reafirmadas após o bloco.
9. **Janela de contexto:** antes de enviar, o provedor obtém do Ollama o contexto máximo do modelo e calcula `num_ctx` com uma estimativa conservadora dos tokens do prompt mais a reserva da resposta. Se não couber, a operação falha antes de qualquer envio (`PromptTooLargeException`). Sem esse controle, o Ollama usa 4k tokens abaixo de 24 GiB de VRAM e corta em silêncio o início de prompts maiores, que é onde ficam as regras de segurança (medido: 109.951 caracteres viraram 2.050 tokens). `num_predict` limita a resposta à reserva, para que a geração não empurre o prompt para fora da janela.
10. **Validação do provedor:** corpos de erro são descartados, o acúmulo da resposta é limitado e o marcador de conclusão do Ollama precisa indicar resultado completo. A resposta NDJSON tem teto de 16 MiB e é recebida dentro da troca HTTP com prazo. Se o `prompt_eval_count` informado for implausivelmente baixo para o tamanho do prompt, a resposta é descartada (`PromptTruncatedException`).
11. **Validação da saída:** o texto gerado é redigido de novo, limitado a 2.000.000 caracteres, verificado quanto a Unicode e caracteres de controle, obrigado a começar com H1 e restrito a Markdown passivo com links web ou relativos.
12. **Publicação transacional:** o writer repete o preflight, prepara as duas saídas por canais `CREATE_NEW` relativos a handles, força a gravação em disco onde há suporte e só então publica o par com reversão. O diretório de saída precisa existir e ser confiável e não compartilhado. Diretórios POSIX graváveis por grupo ou outros, caminhos com symlink ou não verificáveis são recusados. Destinos existentes exigem `--force`.
13. **Inspeção:** `--dry-run` e `--preview-prompt` não contatam provedores. Pré-visualizações usam permissões privadas POSIX quando disponíveis e não são substituídas sem `--force`.

## Aplicação web (docgen-api + docgen-web)

### Exposição de rede

- O servidor escuta só em `127.0.0.1` (`server.address`). Expor o DocGen em outras interfaces ou na internet não é suportado.
- **Defesa contra DNS rebinding:** o `HostHeaderFilter` só atende requisições cujo host seja `localhost`, `127.0.0.1` ou `::1` (configurável em `docgen.api.allowed-hosts`); outros hosts recebem `400 invalid_host`.
- **CORS:** nenhuma origem cruzada é autorizada. Não existe configuração de CORS, preflights de outras origens não recebem `Access-Control-Allow-Origin` e o frontend é servido na mesma origem.

### Autenticação

- Credenciais definidas pelo operador em `DOCGEN_API_USERNAME` e `DOCGEN_API_PASSWORD` (mínimo de 12 caracteres), codificadas com BCrypt na inicialização. Usuários adicionais só existem para ambientes locais e testes (`docgen.api.additional-users`).
- **Web:** `POST /api/v1/auth/token` emite um token opaco de 256 bits. O servidor guarda apenas o hash SHA-256, em memória, com expiração absoluta (8 h), por inatividade (30 min) e no máximo 10 tokens por usuário. O navegador guarda o token no `sessionStorage` da aba e o envia em `Authorization: Bearer`. Logout revoga o token, e reiniciar o servidor encerra todas as sessões.
- **Scripts:** HTTP Basic continua aceito (stateless). Respostas 401 para o workspace (`X-DocGen-Client`) usam o desafio `Bearer`, para que o navegador nunca mostre o diálogo nativo de Basic nem guarde credenciais que possam ser reenviadas automaticamente.
- **Força bruta:** o `ThrottledAuthenticationManager` aplica o mesmo bloqueio progressivo (30 s, dobrando até 15 min, após 5 falhas) ao Basic e ao endpoint de token, por nome de usuário. A mensagem de falha é a mesma para usuário inexistente e para senha errada.
- Sem sessão HTTP (`STATELESS`), sem cookies, sem `formLogin`.

### CSRF

O CSRF depende de o navegador anexar credenciais automaticamente. Aqui, nenhuma credencial é ambiente (não há cookies e o Basic nunca é armazenado pelo navegador). Além disso, o `RequestPolicyFilter` exige que todo `POST`, `PUT` ou `PATCH` em `/api/` declare um Content-Type não simples (`application/json`, ou `application/pdf` para upload). Um formulário cross-site não consegue enviar esses tipos sem preflight CORS, que nunca é concedido. Por isso os tokens CSRF baseados em cookie do Spring estão desativados, de forma deliberada e documentada em `SecurityConfig`.

### Autorização e isolamento

- `/api/v1/**` exige o papel `ANALYST`, exceto `health`, `providers` e `auth/token`. Rotas desconhecidas são negadas (`denyAll`), e só os arquivos estáticos do workspace são públicos.
- Todo recurso (projeto, anexo, plano, artefato, job) tem `owner`, e toda consulta filtra por `id` e `owner`. Recursos de outro usuário respondem `404`, sem revelar existência. IDs são UUIDs aleatórios validados antes de qualquer consulta.
- Repositórios do servidor só podem ser analisados por identificadores opacos mapeados pelo operador (`docgen.repositories`), com caminhos absolutos, normalizados e sem symlinks. Clientes nunca enviam caminhos.

### Validação de entrada e limites

- DTOs separados do domínio, com Bean Validation (tamanhos, padrões, quantidade de itens). A rigidez do JSON é aplicada em código (`StrictJsonConfig`): propriedades desconhecidas, tokens excedentes, chaves duplicadas e `null` em primitivos falham; profundidade máxima de 32 e strings de até 256 KiB. O domínio (`ProjectBrief`) normaliza e limita de novo.
- Tamanhos de corpo por rota, verificados pelo Content-Length e durante a leitura: 16 KiB (análise), 64 KiB (JSON em geral), 1 MiB (edição de documento) e 10 MiB (PDF).
- Limites de recursos: 200 projetos por usuário, 5 anexos por projeto, 3 jobs ativos por usuário, fila de 16 jobs, 2 workers, timeout de 15 min por job, 60 s e 200 páginas por PDF.

### Upload e processamento de PDF

- Aceito apenas como corpo `application/pdf` (multipart desativado) e com assinatura `%PDF-`. A estrutura é validada pelo próprio parser.
- O stream é gravado num arquivo de staging com nome aleatório e permissão `0600`, dentro de um diretório privado `0700`. O nome enviado pelo cliente nunca é usado como caminho: serve só para exibição, depois de decodificado, reduzido ao último segmento, normalizado e limitado a 120 caracteres.
- O PDFBox 3.0.8 só extrai texto: não renderiza nem executa JavaScript, ações, formulários, anexos embutidos ou links. A extração roda num pool dedicado com prazo de 60 s, cancelamento cooperativo entre páginas, orçamento de memória e scratch (32 MiB e 256 MiB) e limites de páginas e caracteres.
- PDFs criptografados ou protegidos por senha são rejeitados (`pdf_encrypted`). PDFs sem camada de texto, como os digitalizados, são rejeitados (`pdf_no_text`): **OCR não é suportado**.
- O texto extraído é normalizado (controles e caracteres invisíveis removidos) e redigido antes de ser persistido. O arquivo temporário é apagado em qualquer desfecho, e o staging é limpo na inicialização.
- Duplicatas (mesmo SHA-256 no projeto) são rejeitadas. Os logs do PDFBox ficam em `ERROR`, para não registrar detalhes derivados do documento.

### Somente local

- O único provedor é o Ollama em `127.0.0.1`. O host é o literal de loopback, sem resolução de nome e sem configuração; só a porta muda (`docgen.ollama.port`, padrão `11434`). Não existe provedor remoto, chave de API nem consentimento de envio, porque nada sai do computador.
- Um modelo que não está instalado gera erro (`model_not_installed`), e o DocGen nunca pede download ao Ollama por conta própria.
- `LocalOnlyArchitectureTest` reprova o build quando o código de produção de qualquer módulo contém uma URI HTTP(S) fora do loopback (salvo identificadores que nunca são buscados, como o namespace do SVG), usa APIs de conexão fora do cliente Ollama ou implementa outro `LLMProvider`. A varredura não prova a ausência de tráfego em tempo de execução, mas impede as regressões óbvias. Os testes do frontend fazem o mesmo para URLs absolutas e caminhos fora de `/api/v1/`.

### Proteção contra XSS e conteúdo ativo

- **CSP estrita** em todas as respostas: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; object-src 'none'`. Além disso: `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` restritiva, `Cross-Origin-Opener-Policy` e `Cross-Origin-Resource-Policy: same-origin`, e `Cache-Control: no-store`.
- O frontend não usa `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval` nem `new Function`. Todo texto não confiável vira nó de texto, atributos de evento inline são recusados pelo helper de DOM e URLs passam por allowlist (`http`, `https`, `mailto`, relativas e âncoras). Um teste automatizado varre o código-fonte procurando esses sinks.
- O Markdown é renderizado por um parser próprio: HTML bruto aparece como texto literal, imagens não são carregadas (só o texto alternativo aparece) e links externos recebem `rel="noopener noreferrer nofollow"`.
- **Diagramas:** o navegador não executa Mermaid. O SVG é desenhado a partir da estrutura validada, e o texto Mermaid exportado é gerado com IDs próprios, rótulos com allowlist de caracteres e validado contra uma gramática mínima (sem `click`, `style`, `classDef`, diretivas, links ou HTML).
- **Saída do modelo:** o plano passa pelo `ArchitecturePlanParser` (limites, redação, enums seguros, referências verificadas). Documentos passam pelo `DocumentationValidator` (Markdown passivo); a edição humana é validada da mesma forma no servidor.

### Erros e logs

- Respostas de erro têm códigos estáveis e mensagens fixas; `server.error.include-*` está desativado e exceções nunca são ecoadas.
- Falhas de jobs são classificadas por tipo. O log registra só o código e a classe da exceção, nunca prompts, conteúdo de PDFs, respostas de provedores ou tokens.

## Integridade de releases

As releases publicam `docgen.jar`, um SBOM CycloneDX 1.6 (`docgen.cdx.json`), checksums SHA-256 e bundles Sigstore (`*.sigstore.json`) para os quatro arquivos. A assinatura é keyless, feita pelo workflow de release via OIDC do GitHub Actions. O instalador baixa os artefatos da release mais recente de `brunocorreia-dev/Docgen`, interpreta e verifica estritamente o checksum do JAR e, quando o cosign está disponível, verifica as assinaturas do JAR e do checksum contra a identidade do workflow de release numa tag. `DOCGEN_REQUIRE_SIGNATURE=1` torna o cosign obrigatório; `DOCGEN_SKIP_SIGNATURE=1` dispensa explicitamente a verificação de proveniência.

Com a reorganização em módulos, o SBOM de release continua descrevendo exatamente o `docgen.jar` (core, Jackson e Picocli), e a API recebe um SBOM próprio (`docgen-api.cdx.json`, gerado na CI). O JAR da API ainda não é publicado em releases.

**Modelo de confiança:** com a verificação de assinatura ativa, a assinatura autentica a identidade do workflow de release deste repositório e a tag. O workflow revisado garante o vínculo com o build, porque assina apenas artefatos do job de build somente leitura da mesma execução. Comprometer só a release do GitHub não permite trocar JAR e checksum juntos sem detecção. Sem cosign, o checksum detecta corrupção, mas não a substituição maliciosa de artefato e checksum ao mesmo tempo. As assinaturas não protegem contra o comprometimento do repositório ou da definição do workflow; revise mudanças em workflows e nos SHAs fixados.

## Verificações automatizadas

- CodeQL analisa Java e JavaScript com a suíte `security-extended` em pushes e pull requests.
- O GitHub Dependency Review rejeita pull requests que introduzam dependências com vulnerabilidades de severidade moderada ou maior.
- O OSV-Scanner faz varredura completa em pushes, pull requests, semanalmente e em tags de release. Uma vulnerabilidade faz o job falhar, e o build de release depende dessa verificação.
- `mvn verify` precisa gerar os SBOMs; a CI também roda os testes do frontend com `node --test`.
- As permissões dos workflows são nulas por padrão e concedidas job a job. Actions e workflows reutilizáveis externos estão fixados por SHA completo, e os checkouts não persistem credenciais.
- Builds e testes de release rodam com `contents: read`. Só um job separado, que não faz checkout nem executa código do projeto, recebe `contents: write` e `id-token: write` para assinar e publicar os artefatos já construídos.

Para tornar essas verificações obrigatórias antes de merges, os administradores precisam configurar branch protection ou rulesets que exijam os jobs da CI; falhas de workflow sozinhas não impedem um merge.

## Como reportar problemas

Abra um security advisory privado ou contate o proprietário do repositório antes de divulgar uma vulnerabilidade.

## Limitações conhecidas

- **A redação de segredos é baseada em padrões.** Ela cobre formatos comuns de tokens de nuvem e SaaS (AWS, Google, GitHub, GitLab, Slack, Stripe, SendGrid, Twilio, npm, PyPI, Hugging Face, DigitalOcean, Shopify, Azure, JWTs, blocos de chave privada, credenciais em URLs e em atribuições), mas não reconhece formatos proprietários ou sem prefixo, segredos divididos em linhas nem valores codificados ou ofuscados. Isso vale também para PDFs: não anexe documentos com credenciais. Use `.docgenignore`, `--dry-run` e `--preview-prompt` para inspecionar antes de enviar.
- **A janela de contexto vem de uma estimativa.** O Ollama não oferece contagem exata de tokens, então o `num_ctx` sai de uma estimativa calibrada contra o tokenizador Qwen2.5. Ela ficou acima da contagem real em todas as classes de texto medidas (código, prosa, JSON, CJK, emoji, base64, hex, números e pontuação), mas um modelo de outra família ou um texto fora dessas classes pode tokenizar pior. A checagem do `prompt_eval_count` é a segunda barreira e só detecta truncamentos grandes.
- **Modelos de nuvem do Ollama ainda não são bloqueados.** O próprio Ollama pode executar modelos na nuvem de ollama.com. Nesta versão, o DocGen não impede que `--model` aponte para um modelo desses; o bloqueio em camadas está planejado para a Fase 3. Até lá, use só modelos locais ou ligue o modo só local do Ollama (`OLLAMA_NO_CLOUD=1`, segundo a documentação oficial).
- **As defesas contra prompt injection são mitigações, não garantias.** Delimitadores de dado não confiável, neutralização de tags e reforço após o bloco reduzem a chance de o conteúdo (do repositório ou de um PDF) direcionar o modelo, mas nenhum prompt elimina esse risco.
- **A validação garante forma, não verdade.** Planos e documentos podem conter erros, recomendações inadequadas ou conteúdo de engenharia social. A aprovação de um plano registra que alguém o revisou; não o torna correto.
- **`.docgenignore` não suporta negação.** Os padrões só excluem; uma linha com `!` faz a varredura falhar em vez de ser ignorada em silêncio.
- **A travessia segura não é portável para todo sistema de arquivos.** Provedores sem `SecureDirectoryStream` falham fechados. Symlinks e renomeações de ancestrais são contidos, mas o Java NIO portátil não distingue hardlink ou bind mount de um inode legítimo dentro da raiz; não analise repositórios graváveis por um usuário local não confiável.
- **A escrita do par é reversível, não um journal contra quedas.** Sincronizações reduzem perdas, mas uma falha abrupta entre publicações pode deixar um arquivo de recuperação ou um par misto que exige inspeção manual. O NIO portátil também não tem unlink ou chmod condicionais por identidade, não preserva todos os ACLs e xattrs e não se defende de um processo hostil da mesma conta alterando um diretório compartilhado entre as verificações. Use um diretório de saída existente, confiável e não compartilhado; em POSIX, ele não pode ser gravável por grupo ou outros usuários.
- **A aplicação web é local e de baixa escala.** Sessões e o controle de tentativas ficam em memória e se perdem ao reiniciar. Não há HTTPS (o tráfego fica no loopback). O token web fica no `sessionStorage` da aba, então um XSS poderia lê-lo; a CSP estrita e a ausência de sinks HTML são as mitigações.
- **PDFs:** sem OCR; o texto pode sair fora de ordem em layouts complexos; o limite de 200.000 caracteres trunca documentos longos, e isso é sinalizado na interface.
- **A verificação de assinatura é tão forte quanto o workflow.** A assinatura keyless autentica a identidade do `release.yml` e a tag; a garantia de que os bytes assinados vieram do build depende de revisar esse workflow. Ela não comprova a ausência de código malicioso no repositório naquela tag.

## Distribuição (v2.0.0+)

As releases publicam dois JARs independentes: `docgen.jar` (CLI) e `docgen-api.jar` (API REST + workspace web). Cada JAR e cada SBOM CycloneDX tem checksum SHA-256 e bundle Sigstore keyless, assinados pelo workflow `release.yml` em uma tag. `install.sh` instala apenas a CLI; `install-web.sh` instala apenas o DocGen Web, com as mesmas verificações de checksum e assinatura.
