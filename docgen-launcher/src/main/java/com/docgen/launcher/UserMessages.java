package com.docgen.launcher;

/**
 * Portuguese texts shown to the person. Technical details (exit codes,
 * exception classes, stack traces) stay in the logs; the window explains
 * what happened and what to do next.
 */
final class UserMessages {
    record Notice(String title, String detail, String hint) {
        String describe() {
            return hint == null || hint.isBlank() ? detail : detail + " " + hint;
        }
    }

    private static final String SEE_LOGS = "Use “Ver logs” para os detalhes técnicos.";

    private UserMessages() {
    }

    static Notice diagnostics(LauncherDiagnostics.Check failure) {
        return switch (failure.category()) {
            case RUNTIME -> new Notice("Runtime Java incompatível", failure.message() + ".",
                    "Reinstale o DocGen ou use um Java " + RuntimeEnvironment.MINIMUM_FEATURE_VERSION + " ou superior.");
            case MISSING_FILES -> new Notice("Arquivos do DocGen ausentes", "Backend do DocGen: " + failure.message(),
                    "Reinstale o DocGen para restaurar os arquivos.");
            case PERMISSION -> new Notice("Permissão negada", failure.label() + ": " + failure.message(),
                    "Verifique as permissões desse diretório ou ajuste DOCGEN_DATA_DIR.");
            case PLATFORM -> new Notice("Ambiente não suportado", failure.message(), null);
        };
    }

    static Notice startup(BackendStartupException failure, long startupTimeoutSeconds) {
        return switch (failure.reason()) {
            case PORT_IN_USE -> new Notice("Porta ocupada",
                    "A porta configurada já está em uso por outro programa. O DocGen não usa nem encerra esse programa.",
                    "Feche o programa que ocupa a porta ou remova DOCGEN_API_PORT para usar uma porta livre automaticamente.");
            case DATA_IN_USE -> new Notice("Dados em uso",
                    "Outra instância do DocGen (por exemplo, o docgen-server) está usando os mesmos dados.",
                    "Encerre a outra instância e tente novamente.");
            case PERMISSION_DENIED -> new Notice("Permissão negada",
                    "O backend não conseguiu ler ou gravar os próprios arquivos.", SEE_LOGS);
            case INCOMPATIBLE_RUNTIME -> new Notice("Runtime Java incompatível",
                    "O backend instalado exige uma versão de Java diferente da disponível.", "Reinstale o DocGen.");
            case LAUNCH_FAILED -> new Notice("Não foi possível iniciar o backend",
                    "O runtime Java do DocGen não pôde ser executado.", "Reinstale o DocGen. " + SEE_LOGS);
            case EXITED -> new Notice("O backend foi encerrado durante a inicialização",
                    "O backend do DocGen parou antes de ficar pronto.", SEE_LOGS);
            case TIMEOUT -> new Notice("Backend indisponível",
                    "O backend não ficou pronto em " + startupTimeoutSeconds + " segundos e foi encerrado.",
                    "Tente novamente; em computadores lentos a primeira inicialização pode demorar mais.");
            case HANDSHAKE_FAILED -> new Notice("Falha na verificação de segurança",
                    "O serviço que respondeu não comprovou ser o backend iniciado por este aplicativo e, por segurança, não será usado.",
                    SEE_LOGS);
            case CRASHED -> new Notice("O backend foi encerrado inesperadamente",
                    "O DocGen deixou de responder no navegador.", "Clique em “Tentar novamente” para reiniciá-lo.");
            case CANCELLED -> new Notice("Inicialização cancelada", "O DocGen foi encerrado durante a inicialização.", null);
        };
    }

    static Notice browserFailed(String address) {
        return new Notice("Não foi possível abrir o navegador",
                "Abra este endereço no seu navegador: " + address + ".",
                "Ele não contém senha; o acesso continua exigindo sua confirmação nesta janela.");
    }

    static Notice browserOpened() {
        return new Notice("Navegador aberto",
                "Confira o código exibido no navegador e confirme nesta janela.", null);
    }

    static Notice pairingClosed(String reason) {
        return switch (reason) {
            case "conflict" -> new Notice("Falha de autenticação",
                    "Duas solicitações de acesso chegaram ao mesmo tempo e, por segurança, ambas foram canceladas.",
                    "Clique em “Abrir DocGen” novamente.");
            case "expired" -> new Notice("Solicitação expirada",
                    "O pedido de acesso do navegador expirou antes da confirmação.", "Clique em “Abrir DocGen” novamente.");
            case "cancelled" -> new Notice("Falha de autenticação",
                    "O pedido de acesso foi cancelado após tentativas inválidas.", "Clique em “Abrir DocGen” novamente.");
            case "denied" -> new Notice("Acesso recusado", "O navegador não recebeu acesso ao DocGen.", null);
            case "completed" -> new Notice("Navegador conectado", "O workspace está aberto no navegador.", null);
            default -> new Notice("Solicitação encerrada", "O pedido de acesso do navegador foi encerrado.", null);
        };
    }

    static Notice alreadyRunning() {
        return new Notice("O DocGen já está aberto",
                "Outra janela do DocGen já está em execução para este usuário.",
                "Use a janela existente ou encerre-a antes de abrir outra.");
    }

    static Notice lockUnavailable(String detail) {
        return new Notice("Não foi possível iniciar o DocGen", detail, "Verifique as permissões do diretório de estado.");
    }
}
