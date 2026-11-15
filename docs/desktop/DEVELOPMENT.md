# DocGen Desktop — Desenvolvimento

## Requisitos

- JDK 21 ou superior (o build compila com `--release 21`).
- Maven 3.9+.
- Node.js 20+ apenas para os testes do frontend (`node:test`, sem dependências npm).
- Para empacotar: um JDK com `jlink` e `jpackage` (veja [INSTALLATION.md](INSTALLATION.md)).

## Módulos

| Módulo | Papel |
|---|---|
| `docgen-desktop-protocol` | Formato `docgen-desktop/1`: segredo de bootstrap, chaves derivadas, mensagens HMAC com sequência e prova de prontidão. Só JDK. |
| `docgen-launcher` | Janela Swing, diagnóstico, gerenciamento do processo, verificação de identidade, pareamento, logs e instância única. Gera `docgen-launcher/target/docgen-launcher.jar` (inclui o protocolo). |
| `docgen-api` | Modo desktop: `DesktopEnvironmentPostProcessor`, `DesktopConfig`, `DesktopLauncherLink`, `DesktopHandshakeController`, `DesktopPairingService`, `DesktopPairingController`. |
| `docgen-web` | Tela de pareamento (`#/desktop`, `js/desktop.js`, `js/views/desktop-pairing.js`). |

## Build e execução a partir do código-fonte

```bash
mvn -B verify                                   # testes de todos os módulos + jars + SBOMs
java -jar docgen-launcher/target/docgen-launcher.jar --diagnose
java -jar docgen-launcher/target/docgen-launcher.jar --check      # sem janela: inicia, verifica e encerra
java -jar docgen-launcher/target/docgen-launcher.jar             # janela (precisa de ambiente gráfico)
```

Num checkout, o launcher encontra `docgen-api/target/docgen-api.jar` pela própria localização (não pelo
diretório atual). Para outro jar: `--backend-jar /caminho/docgen-api.jar`. Para testar sem tocar nos seus
dados reais, isole o perfil:

```bash
DOCGEN_DATA_DIR=/tmp/docgen-dev/data XDG_STATE_HOME=/tmp/docgen-dev/state \
  java -jar docgen-launcher/target/docgen-launcher.jar --check
```

Opções: `--port <n>` (porta fixa; padrão `DOCGEN_API_PORT` ou porta efêmera), `--startup-timeout <s>`
(10–600, padrão 90), `--backend-jar`, `--diagnose`, `--check`, `--version`, `--help`.

O backend em modo desktop só sobe com o segredo no stdin (`docgen.desktop.enabled=true` sem launcher falha
após `docgen.desktop.bootstrap-timeout`, 30 s por padrão). Para o servidor tradicional, nada mudou:
`./docgen-server`.

## Testes

```bash
mvn -B -pl docgen-launcher -am test          # protocolo + launcher (sem o jar da API, os testes ponta a ponta são pulados)
mvn -B verify                                # tudo; os testes ponta a ponta usam o docgen-api.jar recém-empacotado
node --test docgen-web/src/test/js/*.test.mjs
```

| Classe | Cobertura |
|---|---|
| `DesktopProtocolTest` | Bootstrap estrito, segredo nunca em `toString`, MACs por direção, replay/reordenação, prova de prontidão vinculada a nonce/pid/porta/segredo, separação de chaves. |
| `PlatformDetectorTest`, `InstallationLayoutTest`, `LauncherDiagnosticsTest` | SO e arquitetura (inclusive desconhecidos), convenções XDG/macOS/Windows, caminhos inválidos, layout empacotado/portátil/desenvolvimento, links simbólicos, permissões insuficientes, runtime < 21, jar ausente/corrompido/estranho. |
| `BackendProcessManagerTest` (processos reais simulados por `FakeBackend`) | Sucesso, inicialização duplicada, saída prematura classificada, timeout, prova errada, JSON forjado, pid errado, serviço estranho na porta (que continua vivo), porta fixa ocupada (nada é executado), MAC inválido, SIGTERM, kill forçado, queda após pronto e reinício, comandos autenticados, segredos fora dos logs, netos encerrados, cancelamento. |
| `SessionPairingCoordinatorTest`, `LauncherControllerTest` | Pareamento só quando armado, código obrigatório, expiração; fluxo completo da janela com UI gravadora, navegador que falha, confirmação ao fechar, encerramento durante a inicialização. |
| `LauncherPanelTest`, `UserMessagesTest` | Estados reais, mnemônicos, nomes acessíveis, mensagens sem stack trace, renderização offscreen em `docgen-launcher/target/ui-preview/*.png`. |
| `SingleInstanceLockTest` | Lock entre processos, liberação após kill, lock plantado como link, allowlist de endereços do navegador. |
| `DesktopEndToEndTest` | Launcher + `docgen-api.jar` real: prontidão, pareamento completo como navegador, token aceito, senha de ambiente ignorada, tokens fora dos logs, recusa sem segredo, recusa fora de loopback, recusa com usuários extras. |
| API: `DesktopHandshakeTest`, `DesktopReadinessIntegrationTest`, `DesktopLauncherLinkTest`, `DesktopPairingTest`, `DesktopPairingExpiryTest`, `DesktopModeDisabledTest` | Prova legítima e com segredo errado, nonce reutilizado/malformado, origem, CSRF, CORS, DNS rebinding, senha desativada, canal stdin, pareamento (conflito, negação, código, segredo de consulta, substituição, expiração) e ausência total das rotas no modo tradicional. |
| Web: `desktop.test.mjs` | Detecção do modo, segredo só no corpo, códigos de erro, abandono do polling. |

Os testes não abrem janelas (`java.awt.headless=true`); a janela real precisa de verificação manual.

## Estendendo o protocolo

1. Novo tipo de evento/comando: só `[A-Z_]` no tipo e `[A-Za-z0-9._:-]{1,64}` nos campos.
2. Emita com `DesktopLauncherLink.emit(...)` na API ou `BackendProcessManager.send(...)` no launcher; o
   receptor recebe a mensagem já verificada.
3. Nunca coloque segredos em campos: as linhas passam pelo mesmo stdout dos logs.
4. Mudança incompatível de formato exige `docgen-desktop/2`.
