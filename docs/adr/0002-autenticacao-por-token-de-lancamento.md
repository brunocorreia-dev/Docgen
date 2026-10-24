# ADR 0002: Autenticação por token de lançamento e cookie de sessão

| | |
|---|---|
| Estado | **Aceita na essência** (decisão 4.2 do pedido de realinhamento). Os itens marcados "pendente" aguardam aprovação. |
| Data | 2026-10-24 |
| Fase | Proposta na Fase 0; implementação na Fase 2 |
| Relacionadas | [ADR 0001](0001-shell-desktop.md), [ADR 0003](0003-somente-local.md), [roadmap](../desktop/ROADMAP.md) |

## Contexto

Hoje o acesso exige `DOCGEN_API_USERNAME` e `DOCGEN_API_PASSWORD` no ambiente, uma tela de login e um token
Bearer guardado no `sessionStorage` da aba. Isso contraria o princípio "sem terminal para o usuário final".

A API escuta só em `127.0.0.1`, mas "local" não significa "confiável". Modelo de ameaça:

| Agente | Em escopo? | Observação |
|---|---|---|
| Páginas web abertas no mesmo navegador (CSRF, DNS rebinding, vazamentos entre sites) | **Sim** | Principal ameaça de uma API local. |
| Outros usuários do mesmo computador | **Sim** | Conseguem abrir conexões para `127.0.0.1` e, no Linux, ler a linha de comando dos processos alheios (`/proc/<pid>/cmdline`) por padrão. |
| Outros serviços escutando em `127.0.0.1` em outras portas | **Sim** | Cookies não são isolados por porta (RFC 6265, §8.5). |
| Processos do **mesmo** usuário | Não | Já leem o diretório de dados, o banco H2 e o `instance.json`. Equivalem ao próprio usuário. |
| Rede | Não se aplica | O servidor não escuta fora do loopback. |

## Decisão

### Token de lançamento

- A cada abertura (inclusive na reabertura pedida por uma segunda execução), o launcher gera um token de
  **32 bytes** (`SecureRandom`, 256 bits) em base64url.
- O token é de **uso único**, vale **60 segundos** (proposta; cobre um navegador lento abrindo a frio) e
  fica na memória **só como hash SHA-256**. São no máximo 4 tokens pendentes.
- O launcher abre `http://127.0.0.1:<porta>/launch#<token>`. O fragmento nunca vai ao servidor (não aparece
  em log de acesso) nem ao cabeçalho `Referer` (a especificação Fetch remove o fragmento, e a resposta já
  manda `Referrer-Policy: no-referrer`).
- A página `/launch` é estática (HTML mais um script próprio, compatível com `script-src 'self'`). Ela:
  1. lê o fragmento e o apaga na hora com `history.replaceState`;
  2. envia `POST /api/v1/session` com `{"launchToken": …}`, `credentials: 'same-origin'` e
     `redirect: 'error'`;
  3. com sucesso, vai para `/` com `location.replace`;
  4. com falha, mostra em PT-BR: "Este link de acesso expirou ou já foi usado. Abra o DocGen de novo pelo
     menu ou pelo atalho."

### Sessão

- A troca devolve `Set-Cookie: docgen_session=<valor>; Path=/; HttpOnly; SameSite=Strict`, sem `Domain` (o
  cookie fica preso ao host) e sem `Max-Age` (morre quando o navegador fecha). Não usa `Secure`, porque a
  conexão é HTTP de loopback.
- O valor tem 256 bits aleatórios; o servidor guarda só o hash, em memória. Reiniciar o app derruba todas
  as sessões. São no máximo 8 sessões ativas, e a mais antiga sai quando o limite é atingido.
- **Pendente:** validade. Proponho durar o tempo de vida do processo, com expiração por inatividade de 12 h.
  Hoje são 30 min, o que num app desktop obrigaria a reabrir pelo atalho a cada pausa longa.
- O filtro de autenticação lê **só** o cookie: `Authorization: Bearer` e `Basic` passam a ser ignorados.
  Saem `AuthController`, `LoginThrottle`, `ThrottledAuthenticationManager`, o `UserDetailsService` e a tela
  `login.js`.
- O dono dos recursos passa a ser fixo (proposta: `local`), com migração dos donos antigos. O filtro por
  `owner` continua em todas as consultas.

### Proteções do servidor

| Proteção | Regra |
|---|---|
| DNS rebinding | O `Host` precisa ser exatamente `127.0.0.1:<porta>` ou `localhost:<porta>`; o resto recebe `400 invalid_host`. Hoje só o nome do host é conferido, e `::1` também é aceito. |
| CSRF | Em `POST`, `PUT`, `PATCH` e `DELETE` sob `/api/`: `Origin` **obrigatório** e igual a `http://127.0.0.1:<porta>` ou `http://localhost:<porta>`; se `Sec-Fetch-Site` vier, tem que ser `same-origin`. Continua a regra de Content-Type não simples. Somado ao `SameSite=Strict`, isso cobre também navegadores que não mandam `Sec-Fetch-*`. Recusa: `403 origin_rejected`. |
| CORS | Nenhuma origem cruzada é autorizada (como hoje). |
| Reabertura pela segunda execução | `POST /api/v1/desktop/reopen` não usa cookie: exige o cabeçalho com o segredo de instância (comparação em tempo constante) e exige **ausência** de `Origin` e `Sec-Fetch-*`. Navegadores atuais mandam `Origin` em todo `POST` (a confirmar nos três SOs na Fase 2), o que impede uma página de usar essa rota mesmo que adivinhasse o segredo. |
| Logs | Token de lançamento, cookie e segredo de instância nunca são registrados. O log de acesso do Tomcat continua desligado. |
| CSP e cabeçalhos | Sem mudança: `default-src 'none'`, `script-src 'self'`, `connect-src 'self'` etc. |

## Riscos residuais (declarados, não escondidos)

1. **O token aparece na linha de comando do navegador** até a troca. Outro usuário do mesmo Linux que
   monitore `/proc` pode tentar usá-lo primeiro.
   - Mitigações: validade curta e uso único. Os hashes consumidos ficam guardados até expirar; se o
     navegador legítimo chegar com um token já usado, a sessão criada com ele é revogada, a API responde
     `409 launch_token_reused` e a página explica o que aconteceu.
   - Isso **detecta e corta** o acesso, mas não impede os segundos de acesso entre o roubo e a chegada do
     navegador legítimo.
   - Endurecimento a avaliar na Fase 2: abrir um arquivo HTML local privado que redireciona para a URL com o
     token, para o token não aparecer na linha de comando. Ressalva a verificar: navegadores em Snap ou
     Flatpak podem não ter acesso a diretórios ocultos do usuário.
2. **O cookie vai para qualquer porta de `127.0.0.1`** quando o navegador acessa outro serviço local por uma
   navegação do mesmo site (RFC 6265, §8.5). Mitigações:
   - `SameSite=Strict`, que bloqueia o envio a partir de sites externos;
   - sessão que morre com o app;
   - a UI não transforma em link clicável URLs de loopback ou de rede privada vindas do modelo (uma resposta
     manipulada por *prompt injection* poderia levar o usuário a clicar).
3. **XSS na UI** daria acesso à API dentro da página. O `HttpOnly` impede ler o cookie, mas não impede agir
   pela página. As mitigações seguem as de hoje: CSP estrita, nenhum *sink* HTML e o teste que varre o
   código procurando esses *sinks*.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Manter usuário e senha | Exige terminal ou configuração e contradiz o objetivo. |
| Pareamento com código confirmado numa janela confiável (branch do launcher) | Precisa de uma segunda UI confiável (Swing) e de uma confirmação manual a cada abertura. |
| Token de lançamento trocado por Bearer em `sessionStorage` | Sem credencial ambiente: nada de CSRF e nada de vazamento entre portas. Em troca, o token fica legível por XSS e se perde em abas novas. O pedido escolheu cookie; esta opção fica como plano B se o risco residual 2 for considerado inaceitável. |
| Subdomínio aleatório de `localhost` para isolar o cookie | A resolução de `*.localhost` não é uniforme entre SOs e navegadores (não verificado a fundo); a decisão 4.2 também fixa os hosts aceitos. |
| Token na *query string* | Iria para logs, para o `Referer` e para o histórico com mais facilidade que o fragmento. |
| Esquema de URL próprio ou *native messaging* | Registro por SO e por navegador, complexidade desproporcional. |

## Consequências

- A UI muda: sai o `sessionStorage`, as requisições passam a ser same-origin com cookie, e uma aba nova do
  mesmo navegador já fica autenticada.
- Scripts locais deixam de acessar a API (não existe mais Basic). Era esse o objetivo: outro processo sem o
  token não usa a API.
- Os testes de API trocam a autenticação Basic pela troca do token de lançamento num helper de teste.

## Critérios de aceite (Fase 2), todos com teste automatizado

- Requisição sem cookie, com cookie inválido, só com `Authorization: Bearer` ou só com `Basic` → `401` em
  todas as rotas protegidas (teste parametrizado pela lista de rotas, simulando "outro processo local sem o
  token").
- Token de lançamento: segunda troca falha; troca depois de 60 s falha; reuso revoga a sessão criada.
- `Host` com outro nome ou outra porta → `400`.
- `POST`/`PUT`/`DELETE` com `Origin` cruzado, sem `Origin` ou com `Sec-Fetch-Site: cross-site` → `403`.
- Atributos do cookie conferidos: `HttpOnly`, `SameSite=Strict`, `Path=/` e ausência de `Domain`.
- Reabertura com segredo errado ou com `Origin` → recusada.
