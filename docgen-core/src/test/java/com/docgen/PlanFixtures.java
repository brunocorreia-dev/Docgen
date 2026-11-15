package com.docgen;

import java.util.List;

final class PlanFixtures {
    private PlanFixtures() {
    }

    static ProjectBrief brief() {
        return new ProjectBrief(
                "Inventory API",
                "Quero construir uma API REST de gerenciamento de estoque usando Java, Spring Boot e PostgreSQL.",
                List.of("Cadastrar produtos", "Registrar movimentações de estoque"),
                List.of("Autenticação JWT"),
                List.of("Java", "Spring Boot", "PostgreSQL"),
                List.of("Arquitetura em camadas"),
                ProjectBrief.OutputLanguage.PT_BR);
    }

    static String validPlanJson() {
        return """
                {
                  "summary": "API REST para controle de estoque.",
                  "objectives": ["Centralizar o estoque", "Rastrear movimentações"],
                  "functionalRequirements": [
                    {"description": "Cadastrar produtos", "source": "USER"},
                    {"description": "Emitir relatório de estoque baixo", "source": "AI_SUGGESTION"},
                    {"description": "Exportar inventário em CSV", "source": "USER"}
                  ],
                  "nonFunctionalRequirements": [
                    {"description": "Tempo de resposta abaixo de 300 ms", "source": "DOCUMENT_INFERENCE"}
                  ],
                  "architecture": {"style": "Monólito em camadas", "rationale": "Equipe pequena e domínio simples."},
                  "components": [
                    {"id": "Web Client", "name": "Painel Web", "type": "CLIENT", "responsibility": "Interface", "technologies": ["HTML"]},
                    {"id": "api", "name": "API de Estoque", "type": "SERVICE", "responsibility": "Regras de negócio", "technologies": ["Java", "Spring Boot"]},
                    {"id": "db", "name": "PostgreSQL", "type": "database", "responsibility": "Persistência", "technologies": ["PostgreSQL"]}
                  ],
                  "relationships": [
                    {"from": "Web Client", "to": "api", "description": "REST/HTTPS"},
                    {"from": "api", "to": "db", "description": "JDBC"},
                    {"from": "api", "to": "ghost", "description": "inexistente"}
                  ],
                  "dataFlow": ["Cliente envia requisição", "API persiste no banco"],
                  "technologies": [{"name": "Spring Boot", "category": "framework", "justification": "Preferência do usuário"}],
                  "integrations": [],
                  "securityConsiderations": ["Validar tokens JWT"],
                  "persistence": {"strategy": "PostgreSQL relacional", "details": ["Migrações versionadas"]},
                  "implementationPlan": [
                    {"title": "Fundação", "goal": "Estrutura inicial", "tasks": ["Criar projeto"], "deliverables": ["Repositório"]},
                    {"title": "Domínio", "goal": "Produtos", "tasks": ["CRUD"], "deliverables": ["Endpoints"]}
                  ],
                  "risks": [{"description": "Concorrência em baixas", "impact": "HIGH", "mitigation": "Bloqueio otimista"}],
                  "decisions": [{"title": "Banco", "decision": "PostgreSQL", "rationale": "Pedido do usuário", "alternatives": ["MySQL"]}],
                  "assumptions": ["Um único armazém"],
                  "openQuestions": ["Haverá múltiplos armazéns?"]
                }
                """;
    }
}
