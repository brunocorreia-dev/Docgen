# DocGen Desktop — Solução de problemas

## Ferramentas

```bash
~/.local/opt/DocGen/bin/DocGen --diagnose   # sistema, Java, arquivos e permissões (não inicia nada)
~/.local/opt/DocGen/bin/DocGen --check      # inicia o backend, verifica a identidade e encerra, sem janela
```

Logs (Linux): `~/.local/state/docgen/logs/` (ou `$XDG_STATE_HOME/docgen/logs`).

- `launcher.log`: eventos do launcher (início, porta, verificação, pareamento, encerramento).
- `backend.log`: saída da API.
- `*.1.log` … `*.5.log`: sessões anteriores, úteis depois de uma falha.

Os arquivos são privados (0600), limitados a 8 MiB e passam por redação: o segredo da sessão, tokens Bearer e
atribuições como `password=` nunca são gravados. "Ver logs" na janela mostra as últimas linhas do backend.

## Mensagens da janela

| Mensagem | Causa provável | O que fazer |
|---|---|---|
| **Runtime Java incompatível** | O Java que roda o launcher é anterior ao 21, ou o backend exige outra versão. | Use o pacote (traz o próprio Java) ou um JDK 21+. |
| **Arquivos do DocGen ausentes** | `docgen-api.jar` não foi encontrado, está corrompido ou não é o backend do DocGen. | Reinstale. Em desenvolvimento, rode `mvn -B verify` ou use `--backend-jar`. |
| **Permissão negada** | Um diretório de dados, estado ou logs não pode ser escrito, é um link simbólico ou fica dentro da instalação. | Corrija as permissões do diretório indicado ou ajuste `DOCGEN_DATA_DIR`/`XDG_STATE_HOME`. |
| **Porta ocupada** | `DOCGEN_API_PORT`/`--port` aponta para uma porta já em uso. O DocGen não usa nem encerra esse programa. | Feche o programa que usa a porta ou remova a porta fixa. |
| **Dados em uso** | Outro processo (por exemplo `docgen-server`) está com o banco H2 aberto. | Encerre a outra instância e clique em *Tentar novamente*. |
| **Backend indisponível** | O backend não ficou pronto no tempo limite (90 s) e foi encerrado. | Tente de novo; em máquinas lentas use `--startup-timeout 180`. Veja `backend.log`. |
| **O backend foi encerrado durante a inicialização** | A API saiu antes de ficar pronta. | Veja `backend.log` (a causa costuma estar nas últimas linhas). |
| **Falha na verificação de segurança** | Algo respondeu na porta sem provar ser o backend que este launcher iniciou. Ele não é usado. | Veja `launcher.log`; verifique se algum programa interfere em conexões locais. |
| **O backend foi encerrado inesperadamente** | A API caiu depois de pronta. | *Tentar novamente* inicia um backend novo. |
| **Não foi possível abrir o navegador** | Não há navegador padrão configurado (ou `xdg-open` falhou). | Abra o endereço mostrado (sem senha) e confirme o código na janela. |
| **Falha de autenticação** (pedidos simultâneos) | Dois pedidos de acesso chegaram juntos; ambos foram cancelados por segurança. | Feche abas antigas e clique em *Abrir DocGen* de novo. |
| **Solicitação expirada** | O código não foi confirmado em 2 minutos. | Clique em *Abrir DocGen* de novo. |
| **O DocGen já está aberto** | Outro launcher do mesmo usuário está em execução. | Use a janela existente. |

## No navegador

| Mensagem | O que fazer |
|---|---|
| "Para conectar este navegador, clique em “Abrir DocGen”…" | A aba não foi aberta pelo launcher (ou a sessão expirou). Use o botão na janela do DocGen. |
| "Esta solicitação foi substituída ou cancelada…" | Outra aba foi aberta depois. Use a mais recente. |
| "Sua sessão expirou." | Sessões expiram após 30 min sem uso ou 8 h. Clique em *Abrir DocGen*. |

## Sem ambiente gráfico

`docgen-launcher` sem `DISPLAY` responde "nenhum ambiente gráfico disponível". Use `--check` para validar a
instalação ou o `docgen-server` tradicional para uso em servidor/terminal.

## Processos que sobraram

O launcher encerra o backend ao sair; se o próprio launcher for morto, o backend percebe o stdin fechado e
encerra sozinho. Para conferir: `pgrep -af docgen-api.jar`. O launcher nunca encerra processos que não iniciou.
