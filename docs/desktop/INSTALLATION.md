# DocGen Desktop — Instalação

> Linux: implementado e verificado (app image x86_64 gerado com JDK 27). Windows e macOS: ainda não
> suportados oficialmente (veja [packaging/README.md](../../packaging/README.md)).

O DocGen Desktop traz o próprio runtime Java. Quem só vai usar não precisa instalar JDK, Maven ou Node.js.
Hoje o pacote é gerado a partir do código-fonte; ele ainda não é publicado nas releases do GitHub.

## Gerar o pacote Linux

Requisitos de build: JDK 21+ com `jlink` e `jpackage`, Maven 3.9+.

```bash
git clone https://github.com/brunocorreia-dev/Docgen.git
cd Docgen
mvn -B verify
packaging/linux/build-app-image.sh --jdk /usr/lib/jvm/java-21-openjdk
```

Resultado em `target/desktop/dist/`:

- `DocGen/` — app image (executável `DocGen/bin/DocGen`);
- `DocGen-<versão>-linux-<arquitetura>.tar.gz` e `.sha256`.

O script termina com um smoke test (`DocGen/bin/DocGen --check` num perfil temporário). `--type deb` e
`--type rpm` também existem, mas exigem `dpkg-deb` + `fakeroot` ou `rpmbuild` e ainda não foram verificados.

## Instalar para o seu usuário (sem root)

```bash
packaging/linux/install-user.sh target/desktop/dist/DocGen
```

Cria:

| Caminho | Conteúdo |
|---|---|
| `~/.local/opt/DocGen` (ou `$DOCGEN_INSTALL_DIR`, absoluto) | o aplicativo |
| `~/.local/share/applications/docgen.desktop` | entrada no menu de aplicativos |
| `~/.local/share/icons/hicolor/512x512/apps/docgen.png` | ícone |

Reexecutar o script atualiza a instalação (a versão anterior é substituída inteira). O script se recusa a
substituir links simbólicos ou diretórios que não sejam uma instalação do DocGen.

A partir de um `.tar.gz`, confira o checksum, extraia e use o script do repositório
(`packaging/linux/install-user.sh`; ele não vai dentro do arquivo):

```bash
sha256sum -c DocGen-2.0.0-linux-x64.tar.gz.sha256
tar -xzf DocGen-2.0.0-linux-x64.tar.gz
packaging/linux/install-user.sh ./DocGen
```

O checksum detecta corrupção, não substituição maliciosa: o pacote desktop ainda não é assinado.

## Usar

1. Abra **DocGen** no menu (ou `~/.local/opt/DocGen/bin/DocGen`).
2. A janela mostra as etapas reais: *Verificando ambiente*, *Inicializando backend*, *Verificando conexão*,
   *DocGen pronto!*.
3. Clique em **Abrir DocGen**. O navegador abre `http://127.0.0.1:<porta>/#/desktop` e mostra um código.
4. Confira se a janela do DocGen mostra **o mesmo código** e clique em **Autorizar este navegador**.
   Se não reconhecer o pedido, clique em **Recusar**.
5. Para sair, use **Encerrar** (ou feche a janela e confirme). O backend é encerrado junto.

Atalhos: Alt+A (Abrir DocGen), Alt+L (Ver logs), Alt+E (Encerrar), Alt+T (Tentar novamente),
Alt+U (Autorizar), Alt+R (Recusar), Alt+C (Copiar endereço).

## Configuração opcional

O launcher respeita as variáveis já existentes do DocGen; ele não habilita nada por conta própria.

| Variável | Efeito no desktop |
|---|---|
| `DOCGEN_DATA_DIR` | Diretório dos projetos (padrão `~/.local/share/docgen`, o mesmo do `docgen-server`). |
| `DOCGEN_API_USERNAME` | Dono dos projetos; padrão: o nome da sua conta. Use o mesmo valor do `docgen-server` para ver os mesmos projetos. |
| `DOCGEN_API_PORT` | Porta fixa em 127.0.0.1; sem ela, uma porta livre é escolhida pelo próprio backend. |
| `DOCGEN_ALLOW_REMOTE`, `GROQ_API_KEY` | Repassadas ao backend se você as definir; o launcher nunca as ativa. Cada envio ao Groq ainda pede consentimento na tela. |
| `DOCGEN_API_PASSWORD` | Ignorada: no modo desktop não existe login por senha. |

Ollama não é instalado nem baixado pelo DocGen; se quiser IA local, instale-o à parte.

## Desinstalar

```bash
packaging/linux/install-user.sh --uninstall
```

Remove o aplicativo, a entrada de menu e o ícone. Projetos (`~/.local/share/docgen`) e logs
(`~/.local/state/docgen`) são mantidos; apague-os manualmente se quiser.
