# Empacotamento do DocGen Desktop

O aplicativo desktop é o `docgen-launcher.jar` (janela Swing) mais o `docgen-api.jar` (backend Spring Boot),
empacotados com um runtime Java próprio gerado por `jlink`. O usuário final não precisa instalar um JDK.

| Plataforma | Estado | Como gerar |
|---|---|---|
| Linux (app image + `.tar.gz`) | **Implementado e verificado** (JDK 27, x86_64) | `packaging/linux/build-app-image.sh` |
| Linux `.deb` / `.rpm` | Implementado no script, **não verificado** (exige `dpkg-deb` + `fakeroot` ou `rpmbuild`) | `--type deb` / `--type rpm` |
| Windows | **Planejado, não testado** | ver abaixo |
| macOS | **Planejado, não testado** | ver abaixo |

## Linux

```bash
mvn -B verify                                          # testes + docgen-launcher.jar + docgen-api.jar
packaging/linux/build-app-image.sh --jdk /caminho/jdk-21-ou-superior
packaging/linux/install-user.sh target/desktop/dist/DocGen   # instala em ~/.local/opt/DocGen + menu
```

O script:

1. gera um runtime com `jlink` (módulos levantados com `jdeps`, mais `jdk.charsets`, locales `pt`/`en` e
   `jdk.crypto.ec` quando existir) **sem** `--strip-native-commands`, porque o launcher inicia o backend com
   `lib/runtime/bin/java`;
2. desenha o ícone a partir do código (`BrandIcon`), sem binários versionados;
3. roda o `jpackage --type app-image` com o launcher como único classpath e o backend como conteúdo
   (`lib/backend/docgen-api.jar`), e falha se o backend aparecer no classpath do launcher;
4. executa o launcher empacotado com `--check` num perfil temporário (backend real, prova HMAC, encerramento);
5. gera `DocGen-<versão>-linux-<arquitetura>.tar.gz` e o `.sha256`.

Layout do app image:

```text
DocGen/
├── bin/DocGen                      launcher nativo do jpackage
└── lib/
    ├── app/docgen-launcher.jar     classpath do launcher (+ DocGen.cfg)
    ├── backend/docgen-api.jar      backend, iniciado como processo filho
    ├── runtime/                    runtime jlink (inclui bin/java)
    ├── DocGen.png                  ícone
    └── docgen.desktop              modelo da entrada de menu
```

Nada de configuração privada vai para o pacote: não há credenciais de build, o segredo de sessão é gerado
a cada inicialização e dados/logs ficam no perfil do usuário (`~/.local/share/docgen`, `~/.local/state/docgen`).
O pacote não instala nem baixa Ollama ou modelos.

## Windows (planejado, não testado)

Mesma sequência com o `jlink`/`jpackage` de um JDK 21+ para Windows; `--type app-image` ou `--type msi`
(o MSI exige o WiX Toolset). O launcher já resolve `%LOCALAPPDATA%\DocGen\{data,logs,launcher}` e procura o
backend em `<raiz>\backend\docgen-api.jar` (conteúdo do app) ou ao lado do launcher. Ainda falta: script
PowerShell equivalente, validação do encerramento (no Windows `Process.destroy()` é forçado, então o
fechamento do stdin é o único caminho gracioso) e assinatura do instalador.

## macOS (planejado, não testado)

`jpackage --type app-image` ou `--type dmg`, com `--app-content` levando o backend para
`DocGen.app/Contents/backend` (o launcher procura em `Contents/backend`). O launcher usa
`~/Library/Application Support/DocGen` e `~/Library/Logs/DocGen`. Ainda falta: ícone `.icns`, assinatura e
notarização da Apple, e testes em Intel e Apple Silicon.
