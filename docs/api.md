# DocGen Web e API REST

> Referência do workspace web e da API, movida do [README](../README.md). Estado: **✅ disponível para desenvolvedores**. O acesso ainda exige `DOCGEN_API_USERNAME` e `DOCGEN_API_PASSWORD` por variável de ambiente; isso é **temporário** e sai na Fase 2 (launcher sem login, [ADR 0002](adr/0002-autenticacao-por-token-de-lancamento.md)).

## Visão geral

**DocGen Web.** Sobre o mesmo motor, o DocGen agora tem uma API REST (Spring Boot) e um workspace web. Nele você descreve a ideia de um projeto em linguagem natural, anexa PDFs de especificação, recebe da IA uma **proposta estruturada de arquitetura** (componentes, relacionamentos, tecnologias, riscos, decisões e roadmap) com diagrama interativo, revisa e aprova o plano e só então gera, edita e exporta `README.md` e `ARCHITECTURE.md`. A CLI continua independente e com o mesmo comportamento. No app desktop, esses recursos passam a ser as ferramentas de engenharia (Fase 6 do [roadmap](desktop/ROADMAP.md)).

## Funcionalidades

| Funcionalidade | Descrição |
|---|---|
| Projetos | Criação, edição, listagem e exclusão de projetos a partir de descrição, requisitos funcionais e não funcionais, tecnologias, restrições, modelo e idioma. |
| Anexos PDF | Upload com drag-and-drop; extração de texto com Apache PDFBox, redação de segredos e limites rígidos. PDFs digitalizados (OCR) e protegidos por senha não são suportados. |
| Planejamento arquitetural com IA | Plano estruturado e validado: resumo, objetivos, requisitos (rotulados como seus, inferidos de anexo ou sugeridos pela IA), estilo, componentes, relacionamentos, fluxo de dados, tecnologias, integrações, segurança, persistência, fases, riscos, decisões, premissas e perguntas em aberto. |
| Diagrama interativo | SVG desenhado a partir do plano, com zoom, pan, navegação por teclado e destaque de conexões; exportação em SVG e em Mermaid validado no servidor. |
| Revisão humana | Planos nascem como rascunho; a documentação só é gerada a partir de um plano que você aprovou. |
| Documentação | Geração de README e ARCHITECTURE a partir do plano aprovado, preview Markdown com sumário, edição com validação de Markdown passivo e controle de revisão, download e exportação em ZIP. |
| Jobs assíncronos | Planejamento e documentação rodam em fila, com estados reais (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`), cancelamento, timeout e histórico. |
| Histórico | Projetos, planos, documentos e jobs persistidos localmente (H2, com migrações Flyway). |

## Instalação por release (v2.0.0)

> [!WARNING]
> A release mais recente publicada é a **v2.0.0** (2026-10-22). Ela é anterior à versão 3.0 e ainda traz o provedor remoto e o login do workspace. Os instaladores por release abaixo instalam essa versão. Para usar o estado atual (`3.0.0-SNAPSHOT`, somente local), compile pelo código-fonte.

O DocGen Web tem um instalador próprio. O `install.sh` continua instalando só a CLI, como antes.

```bash
curl -fsSL https://raw.githubusercontent.com/brunocorreia-dev/Docgen/main/install-web.sh | sh
```

O `install-web.sh` aplica as mesmas verificações do `install.sh` (HTTPS, checksum estrito, Sigstore com `cosign` e as variáveis `INSTALL_DIR`, `DOCGEN_REQUIRE_SIGNATURE` e `DOCGEN_SKIP_SIGNATURE`), mas baixa `docgen-api.jar` e instala o wrapper `docgen-web`. O frontend vem embutido no JAR e é servido pelo próprio Spring Boot.

## DocGen Web

O DocGen Web é um workspace local de engenharia assistida por IA: tema escuro, navegação lateral, painéis interativos e foco em revisão humana. Ele roda inteiramente na sua máquina (`127.0.0.1`) e consome a API REST real.

*Interface atual do workspace (será substituída pelo app desktop).*

<p align="center">
  <img src="images/dashboard.png" alt="Dashboard do DocGen Web" width="900">
</p>

| Plano e diagrama | Documentação gerada |
|---|---|
| <img src="images/workspace-diagram.png" alt="Diagrama interativo da arquitetura" width="420"> | <img src="images/workspace-docs.png" alt="Revisão da documentação gerada" width="420"> |

> [!IMPORTANT]
> As telas acima mostram resultados reais de um modelo local (Ollama). Planos e documentos são **propostas da IA** e aparecem sempre identificados como tal. Nada neles foi implementado ou validado.

### Fluxo de trabalho

1. **Novo projeto**: descreva a ideia, os requisitos, as tecnologias preferidas e as restrições, e escolha modelo e idioma. O botão "Usar exemplo" preenche dados de demonstração, identificados como tal.
2. **Anexos**: arraste PDFs de especificação. O texto extraído (com segredos redigidos) pode ser conferido em "Ver trecho extraído".
3. **Analisar e gerar plano**: o job entra na fila e roda no Ollama local; o histórico mostra o estado real e permite cancelar.
4. **Revisar**: abas Resumo, Arquitetura, Diagrama e Plano. Requisitos mostram a origem (*Informado por você*, *Inferido de anexo pela IA* ou *Sugestão da IA*). Clique em **Revisei e aprovo** quando o plano fizer sentido.
5. **Documentar**: gere `README.md` e `ARCHITECTURE.md` a partir do plano aprovado, revise no preview, edite (o servidor valida o Markdown passivo) e exporte o ZIP.

### Executar no Arch Linux

Pela release (sem compilar; instala a v2.0.0, veja o aviso em [Instalação por release](#instalação-por-release-v200)):

```bash
sudo pacman -S --needed jre21-openjdk curl cosign   # cosign é opcional, mas recomendado
curl -fsSL https://raw.githubusercontent.com/brunocorreia-dev/Docgen/main/install-web.sh | sh
export DOCGEN_API_USERNAME="$USER"
read -rs -p "Senha (12+ caracteres): " DOCGEN_API_PASSWORD; echo
export DOCGEN_API_PASSWORD
~/.local/bin/docgen-web   # abra http://127.0.0.1:8080
```

Pelo código-fonte:

```bash
# Dependências (Java 21+ e Maven; Node.js só para os testes do frontend)
sudo pacman -S --needed jdk21-openjdk maven nodejs git
sudo archlinux-java set java-21-openjdk   # se houver mais de um JDK

# IA local (obrigatória: o DocGen usa só o Ollama)
sudo pacman -S --needed ollama
sudo systemctl enable --now ollama
ollama pull qwen2.5-coder:7b

# Build
git clone https://github.com/brunocorreia-dev/Docgen.git
cd Docgen
mvn -B verify

# Credenciais do workspace (a senha não fica no histórico do shell)
export DOCGEN_API_USERNAME="$USER"
read -rs -p "Senha (12+ caracteres): " DOCGEN_API_PASSWORD; echo
export DOCGEN_API_PASSWORD

# Iniciar a API e o workspace
./docgen-server
# Abra http://127.0.0.1:8080 no navegador
```

### Configuração por variáveis de ambiente

| Variável | Padrão | Descrição |
|---|---|---|
| `DOCGEN_API_USERNAME` | — (obrigatória) | Usuário do workspace (`[A-Za-z0-9._-]{1,64}`). |
| `DOCGEN_API_PASSWORD` | — (obrigatória) | Senha com 12 a 256 caracteres. |
| `DOCGEN_API_PORT` | `8080` | Porta. O endereço é sempre `127.0.0.1`. |
| `DOCGEN_DATA_DIR` | `$XDG_DATA_HOME/docgen` ou `~/.local/share/docgen` | Diretório privado (`0700`) do banco H2 e do staging de uploads. Precisa ser absoluto. |
| `DOCGEN_OLLAMA_PORT` | `11434` | Porta do Ollama local. O host é sempre `127.0.0.1`. |
| `DOCGEN_JOB_WORKERS` | `2` | Workers simultâneos de IA. |
| `DOCGEN_JOB_TIMEOUT` | `15m` | Tempo máximo por job. |

Outros limites (anexos, retenção, sessão) ficam em `docgen-api/src/main/resources/application.yml` e podem ser sobrescritos com as propriedades `docgen.*` do Spring Boot. Repositórios do servidor para o endpoint de análise são autorizados pelo operador como `docgen.repositories.<id>=/caminho/absoluto`.

## API REST

Base: `http://127.0.0.1:8080/api/v1`. Autenticação: `Authorization: Bearer <token>` (obtido em `POST /auth/token`) ou HTTP Basic para scripts. Toda requisição `POST`/`PUT` precisa de `Content-Type: application/json` (ou `application/pdf` no upload).

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$DOCGEN_API_USERNAME\",\"password\":\"$DOCGEN_API_PASSWORD\"}" | jq -r .accessToken)

# Criar um projeto
PROJECT=$(curl -s -X POST http://127.0.0.1:8080/api/v1/projects \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"API de Estoque","description":"API REST de estoque com Java, Spring Boot e PostgreSQL.","provider":"ollama","model":"qwen2.5-coder:7b"}' | jq -r .id)

# Anexar um PDF (corpo bruto; o nome é só para exibição)
curl -s -X POST "http://127.0.0.1:8080/api/v1/projects/$PROJECT/attachments" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/pdf' \
  -H 'X-Attachment-Name: especificacao.pdf' --data-binary @especificacao.pdf

# Iniciar o planejamento (202 + job) e acompanhar
JOB=$(curl -s -X POST "http://127.0.0.1:8080/api/v1/projects/$PROJECT/plans" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' | jq -r .id)
curl -s "http://127.0.0.1:8080/api/v1/jobs/$JOB" -H "Authorization: Bearer $TOKEN"
```

O planejamento usa o modelo do projeto (no exemplo, `qwen2.5-coder:7b`), que precisa estar instalado no Ollama. Sem ele, o job termina com o erro `model_not_installed`, e nada é baixado.

| Recurso | Rotas |
|---|---|
| Sistema | `GET /health` |
| Sessão | `POST /auth/token`, `GET /auth/session`, `DELETE /auth/token` |
| Projetos | `POST /projects`, `GET /projects`, `GET/PUT/DELETE /projects/{id}` |
| Anexos | `POST/GET /projects/{id}/attachments`, `DELETE /projects/{id}/attachments/{attachmentId}` |
| Planos | `POST/GET /projects/{id}/plans`, `GET /projects/{id}/plans/{planId}`, `POST /projects/{id}/plans/{planId}/approval` |
| Documentação | `POST /projects/{id}/documentation`, `GET /artifacts`, `GET /projects/{id}/artifacts`, `GET/PUT /projects/{id}/artifacts/{artifactId}`, `GET …/{artifactId}/content`, `GET /projects/{id}/export` |
| Jobs | `GET /jobs/{id}`, `POST /jobs/{id}/cancellation`, `GET /projects/{id}/jobs` |
| Repositórios autorizados | `POST /repositories/{id}/analysis` |

Payloads, códigos HTTP e o formato de erro (`{"status","code","message"}`) estão em [ARCHITECTURE.md](../ARCHITECTURE.md#contratos-rest-apiv1).

## Segurança da aplicação web

| Área | Proteção |
|---|---|
| **Aplicação web** | Escuta só em `127.0.0.1`, verifica o Host (contra DNS rebinding), não concede CORS, autentica por token Bearer opaco (sem cookies, sem CSRF possível) com bloqueio progressivo contra força bruta, isola todos os recursos por usuário, valida DTOs estritamente, aplica CSP `script-src 'self'` sem `innerHTML` no frontend e processa PDFs em área privada com limites. Detalhes em [SECURITY.md](../SECURITY.md#aplicação-web-docgen-api--docgen-web). |

## Limitações

- **DocGen Web é local**: sessões e o controle de tentativas ficam em memória (reiniciar o servidor encerra as sessões). Não há HTTPS nem suporte a exposição em rede, e o modelo multiusuário é para uso local.
- **PDFs**: sem OCR (digitalizados são rejeitados), sem PDFs protegidos por senha, até 10 MB, 200 páginas e 200.000 caracteres extraídos por arquivo e 5 anexos por projeto.
- **Planos da IA**: o parser garante estrutura e limites, não a qualidade das recomendações. Modelos pequenos podem gerar planos rasos ou inconsistentes; a aprovação humana é obrigatória antes da documentação.
