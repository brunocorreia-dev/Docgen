# ADR 0002: Autenticação por token de lançamento e token de sessão Bearer

| | |
|---|---|
| Estado | **Aceita e implementada** na Fase 2 (decisão 4.2 do pedido de realinhamento, revisada na aprovação da Fase 0: sessão por Bearer em `sessionStorage`, sem cookies; ajustes da implementação na seção final) |
| Data | 2026-10-24 (revisão no mesmo dia) |
| Fase | Proposta na Fase 0; implementação na Fase 2 |
| Relacionadas | [ADR 0001](0001-shell-desktop.md), [ADR 0003](0003-somente-local.md), [roadmap](../desktop/ROADMAP.md) |

## Contexto

Hoje o acesso exige `DOCGEN_API_USERNAME` e `DOCGEN_API_PASSWORD` no ambiente, uma tela de login e um token
Bearer guardado no `sessionStorage` da aba. Isso contraria o princípio "sem terminal para o usuário final".

A API escuta só em `127.0.0.1`, mas "local" não significa "confiável". Modelo de ameaça:

| Agente | Em escopo? | Observação |
|---|---|---|
| Páginas web abertas no mesmo navegador (CSRF, DNS rebinding, vazamentos entre sites) | **Sim** | Principal ameaça de uma API local. |
| Outros usuários do mesmo computador | **Sim** | Abrem conexões para `127.0.0.1` e, no Linux, leem por padrão a linha de comando dos processos alheios (`/proc/<pid>/cmdline`). |
| Outros serviços escutando em `127.0.0.1` em outras portas | **Sim** | Recebem tudo o que o navegador anexa automaticamente a requisições para eles. |
| Processos do **mesmo** usuário | Não | Já leem o diretório de dados, o banco H2 e o `instance.json`; equivalem ao próprio usuário. |
| Rede | Não se aplica | O servidor não escuta fora do loopback. |

A primeira versão deste ADR trocava o token de lançamento por um cookie `HttpOnly`/`SameSite=Strict`. Ela foi
substituída por um motivo concreto: **cookies não são isolados por porta** (RFC 6265, §8.5, "Weak
Confidentiality"). Um cookie de `127.0.0.1` vai para qualquer serviço em `127.0.0.1:<outra porta>` que o
navegador acessar, e `SameSite` não ajuda, porque o mesmo host em outra porta é o mesmo *site*. Já o
`sessionStorage` é isolado por **origem** (esquema + host + porta) e nunca é anexado automaticamente a nada.

## Decisão

### Token de lançamento

- A cada abertura (inclusive na reabertura pedida por uma segunda execução), o launcher gera um token de
  **32 bytes** (`SecureRandom`, 256 bits) em base64url.
- O token é de **uso único**, vale **30 segundos** e fica na memória **só como hash SHA-256**. São no máximo
  4 tokens pendentes.
- O launcher abre `http://127.0.0.1:<porta>/launch#<token>`. O fragmento nunca vai ao servidor (não aparece
  em log de acesso) nem ao cabeçalho `Referer` (a especificação Fetch remove o fragmento, e a resposta já
  manda `Referrer-Policy: no-referrer`).
- A página `/launch` é estática (HTML mais um script próprio, compatível com `script-src 'self'`). Ela:
  1. lê o fragmento e o apaga na hora com `history.replaceState`;
  2. envia `POST /api/v1/session` com `{"launchToken": …}` (JSON, `credentials: 'omit'`, `redirect: 'error'`);
  3. guarda o token de sessão recebido **só no `sessionStorage`** e vai para `/` com `location.replace`;
  4. com falha, mostra em PT-BR: "Este link de acesso expirou ou já foi usado. Abra o DocGen de novo pelo
     menu ou pelo atalho."

### Sessão

- O token de sessão é um valor opaco de 256 bits, guardado no servidor só como hash e só em memória
  (reiniciar o app encerra todas as sessões). São no máximo 8 sessões ativas, e a mais antiga sai quando o
  limite é atingido.
- O navegador o envia **apenas** no cabeçalho `Authorization: Bearer`. **Não existe cookie** de nenhum tipo.
  `Basic` deixa de ser aceito, e saem `AuthController`, `LoginThrottle`, `ThrottledAuthenticationManager`, o
  `UserDetailsService` e a tela `login.js`.
- **Validade (decidida na aprovação da Fase 1):** expiração por inatividade de 12 h, e sessões **somente em
  memória**: reiniciar o app invalida todas. O heartbeat da UI (ADR 0001) conta como atividade.
- Cada aba tem a própria sessão: uma aba nova não herda o `sessionStorage` e cai na mensagem "abra pelo
  atalho do DocGen". Abrir o app de novo gera um token de lançamento novo.
- **Streaming do chat:** `fetch` + `ReadableStream`, que permite mandar o cabeçalho `Authorization` (o
  `EventSource` não permite).
- O dono dos recursos passa a ser fixo, `local` (decidido na aprovação da Fase 1). A coluna `owner` é mantida
  com esse valor constante, e não removida; a migração `V3` converte os donos antigos. O filtro por `owner`
  continua em todas as consultas.

### Defesa em profundidade no servidor

Sem credencial ambiente, o CSRF clássico não tem como acontecer. Mesmo assim, as validações abaixo ficam:

| Proteção | Regra |
|---|---|
| DNS rebinding | O `Host` precisa ser exatamente `127.0.0.1:<porta>` ou `localhost:<porta>`; o resto recebe `400 invalid_host`. Hoje só o nome do host é conferido, e `::1` também é aceito. |
| Origem | Em `POST`, `PUT`, `PATCH` e `DELETE` sob `/api/`, quando vierem: `Origin` igual a `http://127.0.0.1:<porta>` ou `http://localhost:<porta>`, e `Sec-Fetch-Site` igual a `same-origin`. Continua a regra de Content-Type não simples. Recusa: `403 origin_rejected`. |
| CORS | Nenhuma origem cruzada é autorizada (como hoje). |
| Reabertura pela segunda execução | `POST /api/v1/desktop/reopen` não usa a sessão: exige o cabeçalho com o segredo de instância (do `instance.json`, `0600`, comparação em tempo constante) e exige **ausência** de `Origin` e `Sec-Fetch-*`. Navegadores atuais mandam `Origin` em todo `POST` (a confirmar nos três SOs na Fase 2), o que impede uma página de usar essa rota mesmo que adivinhasse o segredo. |
| Logs | Token de lançamento, token de sessão e segredo de instância nunca são registrados. O log de acesso do Tomcat continua desligado. |
| CSP e cabeçalhos | Sem mudança: `default-src 'none'`, `script-src 'self'`, `connect-src 'self'` etc. |

## Riscos residuais (declarados, não escondidos)

1. **O token de lançamento aparece na linha de comando do navegador** até a troca. Outro usuário do mesmo
   Linux que monitore `/proc` pode tentar usá-lo primeiro.
   - Mitigações: validade de 30 s e uso único. Os hashes consumidos ficam guardados até expirar; se a página
     legítima chegar com um token já usado, a sessão criada com ele é revogada, a API responde
     `409 launch_token_reused` e a página explica o que aconteceu.
   - Isso **detecta e corta** o acesso, mas não impede os segundos de acesso entre o roubo e a chegada da
     página legítima.
   - Endurecimento a avaliar na Fase 2: abrir um arquivo HTML local privado que redireciona para a URL com o
     token, para o token não aparecer na linha de comando. Ressalva a verificar: navegadores em Snap ou
     Flatpak podem não ter acesso a diretórios ocultos do usuário.
2. **XSS na UI** consegue ler o token de sessão no `sessionStorage` (um cookie `HttpOnly` não seria legível,
   mas o XSS agiria pela página do mesmo jeito). Mitigações: CSP estrita, nenhum *sink* HTML, o teste que varre
   o código procurando esses *sinks*, sessão que morre com o app e Markdown do modelo nunca interpretado como
   HTML.
3. **Extensões do navegador** com acesso à página leem o `sessionStorage`. Isso fica fora do controle do app e
   é documentado.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| Manter usuário e senha | Exige terminal ou configuração e contradiz o objetivo. |
| Cookie `HttpOnly` + `SameSite=Strict` (primeira versão deste ADR) | Cookies não são isolados por porta (RFC 6265, §8.5): outro serviço local receberia a credencial. Além disso, cookie é credencial ambiente e reabre a superfície de CSRF. |
| Pareamento com código confirmado numa janela confiável (branch do launcher) | Precisa de uma segunda UI confiável (Swing) e de uma confirmação manual a cada abertura. |
| Token de sessão em `localStorage` | Sobreviveria ao fechamento da aba, mas não ao reinício do app (as sessões são só em memória); só aumentaria a exposição. |
| Token na *query string* | Iria para logs, para o `Referer` e para o histórico com mais facilidade que o fragmento. |
| Esquema de URL próprio ou *native messaging* | Registro por SO e por navegador; complexidade desproporcional. |

## Consequências

- A troca na UI é pequena: o `api.js` já guarda o Bearer no `sessionStorage`; muda só a origem do token (a
  troca do token de lançamento, no lugar do login).
- Scripts locais deixam de acessar a API (não existe mais `Basic`). Era esse o objetivo: outro processo sem o
  token não usa a API.
- Os testes de API trocam a autenticação `Basic` pela troca do token de lançamento num helper de teste.

## Critérios de aceite (Fase 2), todos com teste automatizado

- Requisição sem `Authorization`, com token inválido ou com `Basic` → `401` em todas as rotas protegidas (teste
  parametrizado pela lista de rotas, simulando "outro processo local sem o token").
- Nenhuma resposta da API define cookie (`Set-Cookie` ausente em todas as rotas, inclusive na troca do token).
- Token de lançamento: a segunda troca falha; a troca depois de 30 s falha; o reuso revoga a sessão criada.
- `Host` com outro nome ou outra porta → `400`.
- `POST`/`PUT`/`DELETE` com `Origin` cruzado ou com `Sec-Fetch-Site: cross-site` → `403`.
- Reabertura com segredo errado ou com `Origin` → recusada.

## Implementação (Fase 2, 2026-10-24)

Estado: **Implementado**, com testes. Ajustes e resultados:

- **Retenção dos hashes consumidos: 5 minutos**, e não "até expirar" (30 s). Com 30 s, um token roubado no primeiro segundo e um navegador legítimo que demorasse mais de 30 s para abrir (início a frio numa máquina lenta) resultariam em "link expirado" para o usuário e sessão intacta para quem roubou. Com 5 min, a chegada tardia ainda é detectada como reuso. O custo: durante esses 5 min, quem leu o token também consegue derrubar a sessão legítima reapresentando-o. O servidor não distingue as duas trocas; confidencialidade vem antes de disponibilidade.
- **Endurecimento por arquivo HTML local: avaliado e não adotado.** O arquivo de redirecionamento conteria o próprio token em disco, e navegadores em Snap ou Flatpak (o Firefox padrão do Ubuntu é Snap) não leem diretórios ocultos do usuário, o que quebraria o caso mais comum. A mitigação documentada para computadores compartilhados é montar `/proc` com `hidepid=2`.
- **`Origin` em todo `POST`:** confirmado no Chromium (Linux, via Playwright, contra o JAR real): a troca do token saiu com `Origin: http://127.0.0.1:<porta>`, `Sec-Fetch-Site: same-origin`, sem `Referer` e sem cookie. Firefox e Safari e os demais SOs ficam para a Fase 7. A rota de reabertura não depende disso: ela também exige o segredo de instância.
- **O papel da sessão** passou de `ANALYST` para `LOCAL_SESSION`; o dono é a constante `local`, e a migração `V3` converte os donos da v2.
- **A configuração automática de usuário do Spring Boot** (`UserDetailsServiceAutoConfiguration`) foi excluída: sem ela, nenhum usuário padrão existe e nenhuma senha gerada aparece no log.
- **Testes:** `ProtectedRoutesTest` descobre as rotas pelo `RequestMappingHandlerMapping`, então uma rota nova entra na verificação de `401` sem mexer no teste. Os testes de isolamento entre donos continuam, com linhas de outro dono gravadas direto no banco.

### Critérios de aceite

| Critério | Evidência |
|---|---|
| Sem `Authorization`, com token inválido ou com Basic → `401` em todas as rotas protegidas | `ProtectedRoutesTest` (todas as rotas mapeadas) e `DesktopLauncherTest` sobre HTTP real |
| Nenhuma resposta define cookie | `ProtectedRoutesTest`, `AuthAndSecurityTest`, `DesktopLauncherTest` |
| Segunda troca falha; troca após 30 s falha; reuso revoga a sessão | `LaunchTokenServiceTest`, `AuthAndSecurityTest`, `DesktopLauncherTest` e ponta a ponta no navegador |
| `Host` com outro nome ou outra porta → `400` | `AuthAndSecurityTest` e `DesktopLauncherTest` (com socket bruto) |
| `POST`/`PUT`/`DELETE` com `Origin` cruzado ou `Sec-Fetch-Site: cross-site` → `403` | `AuthAndSecurityTest`, `DataErasureApiTest` |
| Reabertura com segredo errado ou com `Origin` → recusada | `DesktopApiTest`, `DesktopLauncherTest` |
