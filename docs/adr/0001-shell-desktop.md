# ADR 0001: Shell desktop com Spring Boot embutido e UI no navegador

| | |
|---|---|
| Estado | **Aceita e implementada** na Fase 2 (decisão 4.1 do pedido de realinhamento; os pontos que estavam pendentes foram decididos na aprovação da Fase 0; ajustes da implementação na seção final) |
| Data | 2026-10-24 |
| Fase | Proposta na Fase 0; implementação na Fase 2 |
| Relacionadas | [ADR 0002](0002-autenticacao-por-token-de-lancamento.md), [ADR 0003](0003-somente-local.md), [roadmap](../desktop/ROADMAP.md) |

## Contexto

O DocGen precisa parecer um aplicativo comum no Linux, no Windows e no macOS: instalar, abrir por um ícone e
usar sem terminal. A stack atual é Java 21 com Spring Boot 4.1 no backend e uma SPA sem framework e sem build
(HTML, CSS e ES Modules), servida pela própria API em `127.0.0.1`.

Já existe uma tentativa fora de `main`, a branch `feat/docgen-desktop-launcher`: um launcher Swing num
processo separado inicia o `docgen-api.jar` como processo filho, autentica o filho por um protocolo HMAC no
stdin e no stdout e pareia o navegador com um código confirmado na janela Swing. Ela funciona e tem testes
(378 testes Java verdes na medição da Fase 0), mas diverge da decisão 4.1 em três pontos: dois processos,
uma janela Swing além do navegador e um pareamento manual a cada abertura.

## Decisão

1. **Um processo só.** O próprio `docgen-api.jar` é o app. A classe principal passa a ser
   `com.docgen.desktop.DesktopLauncher`, que roda **antes** do Spring:
   1. resolve o diretório de dados do SO (tabela abaixo) e o cria com permissão privada;
   2. tenta pegar a trava `instance.lock` (`FileChannel.tryLock`, liberada pelo SO se o processo morrer);
   3. se a trava já estiver tomada, lê `instance.json` (porta, PID e segredo de reabertura, arquivo `0600`),
      chama `POST /api/v1/desktop/reopen` com esse segredo e termina. Quem abre a nova janela, com um token
      de lançamento novo, é a instância que já estava rodando;
   4. se não, sobe o Spring Boot em `127.0.0.1` na porta `0` (o SO escolhe uma livre, sem a corrida de
      "escolher uma porta e depois tentar usar"), e só depois de o servidor estar pronto grava
      `instance.json` de forma atômica.
2. **Janela = navegador.** Quando o servidor está pronto, o launcher gera um token de lançamento (ADR 0002)
   e abre `http://127.0.0.1:<porta>/launch#<token>`:
   1. primeiro tenta um navegador Chromium conhecido em modo `--app=<url>` (lista fixa de executáveis por SO,
      caminho resolvido, sem shell, argumentos fixos);
   2. se não houver, usa `java.awt.Desktop#browse`;
   3. se também falhar, usa o comando da plataforma (`xdg-open`, `open` ou `rundll32 url.dll,…`), sem shell;
   4. se nada abrir, mostra um diálogo AWT (só fora do modo *headless*) com um botão "Copiar link de acesso",
      que gera um token novo na hora.
3. **Encerramento.** "Sair do DocGen" na UI chama `POST /api/v1/desktop/shutdown` (protegido como qualquer
   requisição que altera estado), que fecha o contexto Spring, o pool do H2 e a trava. Fechar o processo
   pelo SO (sinal ou logout) aciona o *shutdown hook* padrão do Spring.
4. **Bandeja (opcional).** Ícone em `SystemTray` com "Abrir DocGen" e "Sair", só quando
   `SystemTray.isSupported()`. Sem suporte (por exemplo GNOME sem extensão), nada quebra.
5. **Desligamento por inatividade (Fase 2).** Como fechar a aba não encerra o processo, a UI envia um
   heartbeat periódico. O app encerra quando passa **10 minutos sem heartbeat**, e **nunca** enquanto houver
   um download de modelo ou uma geração em andamento. Nesse caso o prazo só começa a contar quando a última
   operação termina.
6. **Diretório de dados por SO:**

   | SO | Diretório | Observação |
   |---|---|---|
   | Linux | `$XDG_DATA_HOME/docgen` ou `~/.local/share/docgen` | É o caminho atual: os dados existentes continuam válidos. |
   | macOS | `~/Library/Application Support/DocGen` | — |
   | Windows | `%LOCALAPPDATA%\DocGen` | Decidido na aprovação da Fase 0: `AppData\Roaming` (`%APPDATA%`) é sincronizado com o servidor em perfis móveis de domínio, o que tiraria as conversas do computador. |

   Variáveis de ambiente só valem com caminho absoluto e normalizado. Os diretórios são criados com `0700`
   onde houver POSIX e nunca podem ser link simbólico.
7. **Log local.** Arquivo rotacionado em `<dados>/logs`, com o mesmo cuidado de hoje: nunca registrar
   prompts, respostas, anexos ou tokens.
8. **Sem Electron, sem Tauri e sem JavaFX nesta etapa.** Também não se cria módulo Maven novo:
   `com.docgen.desktop` vive em `docgen-api`.

## Alternativas consideradas

| Alternativa | Por que não agora |
|---|---|
| **Launcher Swing em processo separado** (a branch existente) | A favor: isola falhas (o launcher sobrevive à queda do backend e o reinicia), tem uma janela para estados de erro e já está implementado e testado no Linux. Contra: duas janelas para o usuário comum; pareamento manual a cada abertura; cerca de 4,4 mil linhas de produção a mais (protocolo HMAC, gestão de processo filho) para manter; e a decisão 4.1 pede a UI no navegador. Proposta: reaproveitar só as peças independentes do modelo de dois processos (ROADMAP §4.6). |
| Electron ou Tauri | Trocariam a stack (Node ou Rust) e o processo de build, aumentariam muito o instalador e trariam uma cadeia de dependências nova. |
| JavaFX WebView | Dependência pesada fora do JDK, motor WebKit próprio com renderização inferior à de um navegador atualizado para a UI existente, e mais módulos no `jlink`. |
| Só o servidor, aberto pela linha de comando | Viola o princípio "sem terminal para o usuário final". |
| Porta fixa (8080) | Conflita com outros programas e cria a corrida "porta ocupada por outro serviço". |

## Consequências

- O app depende de existir um navegador no sistema (praticamente universal) e se parece com um app só
  quando há Chromium para o modo `--app`. Com outro navegador, abre como aba comum.
- A vida do processo não acompanha a vida da janela: daí a bandeja e o desligamento por falta de heartbeat
  (item 5).
- A instância única elimina o erro do H2 "banco em uso" num segundo clique. A trava do arquivo do H2
  continua como segunda barreira.
- O Spring Boot leva alguns segundos para subir sem resposta visual. Mitigação a avaliar na Fase 2: splash
  nativo da JVM (`-splash`, aceito pelo `jpackage` via `--java-options`).
- `docgen-server` passa a apenas iniciar o launcher, para desenvolvimento, e `install-web.sh` sai na Fase 7.

## Critérios de aceite (Fase 2)

- `java -jar docgen-api.jar`, sem nenhuma variável de ambiente, abre a UI autenticada no navegador.
- A segunda execução reabre a mesma instância e não sobe um segundo servidor.
- "Sair" encerra o servidor e libera a trava; uma nova execução sobe limpa.
- Sem heartbeat por 10 minutos, o app encerra; com download ou geração em andamento, não encerra (teste com
  relógio controlado).
- Testes automatizados para trava, reabertura com segredo errado (recusada), escolha de porta e fallback de
  abertura do navegador (com abridor falso).

## Implementação (Fase 2, 2026-10-24)

Estado: **Implementado**, com testes. O que a implementação decidiu ou descobriu:

- **Dependência de mão única.** `com.docgen.desktop` depende da API, e a API não conhece o launcher: ela declara a interface pública `DesktopControl` (abrir janela, encerrar, conferir o segredo de instância), que o launcher implementa e registra como bean antes do refresh do contexto. Por isso os tokens de lançamento ficam em `com.docgen.api.LaunchTokenService` (com `issue()` público), e não no pacote desktop como o roadmap sugeria. Sem o launcher (testes e desenvolvimento), as rotas desktop respondem que o app não está rodando como desktop.
- **Segunda execução.** Com a trava ocupada, o launcher espera até 3 s: se a trava for liberada (a outra instância estava saindo), vira a instância principal; se houver `instance.json` com PID vivo, pede a reabertura; se não houver `instance.json`, a outra instância ainda está subindo e abrirá a própria janela, então a segunda sai em silêncio (sem janela duplicada); se a instância existente não responder, um aviso diz isso. Um `instance.json` deixado por queda (PID morto) é apagado pela próxima instância principal.
- **Encerramento.** O pedido chega numa thread do Tomcat, que é *daemon*, e uma thread herda essa marca. A primeira versão criava a thread de encerramento como daemon sem perceber: a JVM saía assim que o Tomcat parava, antes de apagar o `instance.json`. O teste em processo não pegou (a JVM de teste continua viva); o teste ponta a ponta com o JAR real pegou. A thread agora é explicitamente não daemon, e um teste reprova a regressão.
- **Sinais do SO.** Em `SIGTERM`/logout, o Spring fecha o contexto no próprio *hook*, e o hook do launcher apaga o `instance.json` e libera a trava. Uma compactação pendente fica para o próximo início (rodar com o pool ainda aberto seria arriscado).
- **Splash nativo: não implementado nesta fase.** Ele depende do empacotamento (`-splash:` via `--java-options` do `jpackage`), então fica para a Fase 7. Medido aqui: o app abre a janela em 5 a 6 s; um segundo clique nesse intervalo não abre janela duplicada.
- **Bandeja.** Implementada com `SystemTray` e ícone desenhado em código, só quando há suporte. **Não verificada num desktop real**: o ambiente desta fase é *headless*. Fica para a validação da Fase 7.
- **Navegador.** A lista de Chromium inclui Edge (presente no Windows 10 e 11), Chrome, Chromium e Brave; os caminhos são fixos por SO ou resolvidos só em entradas absolutas do `PATH`. Verificado em Linux com um `xdg-open` substituto e o Chromium do Playwright; macOS e Windows ficam para a Fase 7.
- **Desligamento por inatividade.** O relógio conta a partir do maior entre o último sinal da janela (heartbeat, nova sessão ou reabertura) e o fim da última operação longa; hoje, as gerações da Engenharia (`ActivityTracker` no `JobService`). Downloads e respostas do chat entram nas Fases 3 e 4. Verificado com o JAR real e prazo reduzido por argumento (`--docgen.desktop.idle-timeout=15s`): o app saiu sozinho em 16 s e limpou o estado.
- **Banco após "Apagar todos os meus dados".** O H2 2.4.240 já compacta por até 200 ms ao fechar (`MAX_COMPACT_TIME`, conferido no bytecode), o que cobre bancos pequenos, mas não os grandes. A remoção grava um pedido (`compact-on-exit`), e um `SHUTDOWN COMPACT` roda com o pool fechado: na saída ou no início seguinte.

### Critérios de aceite

| Critério | Evidência |
|---|---|
| `java -jar docgen-api.jar` sem variáveis abre a UI autenticada | `DesktopLauncherTest.startsWithoutAnyConfiguration…` e teste ponta a ponta com o JAR real |
| A segunda execução reabre a mesma instância | `DesktopLauncherTest.aSecondStartReopens…` e ponta a ponta (saiu com 0 em cerca de 1 s, mesma porta, token novo) |
| "Sair" encerra e libera a trava; nova execução sobe limpa | `DesktopLauncherTest.leavingThroughTheUi…` e ponta a ponta |
| 10 min sem heartbeat encerra; download ou geração impedem | `DesktopApiTest` com relógio controlado; ponta a ponta com prazo reduzido |
| Trava, reabertura com segredo errado, porta, abertura do navegador | `InstanceLockTest`, `DesktopLauncherTest.reopeningWithAWrongSecret…`, `BrowserOpenerTest` |
