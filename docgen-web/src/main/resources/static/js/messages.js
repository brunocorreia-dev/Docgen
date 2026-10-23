// User-facing text (pt-BR). The API returns stable error codes with English
// messages; the workspace translates the codes it knows.

const ERRORS = {
  network_error: 'Não foi possível contactar o servidor DocGen. Verifique se a API está em execução.',
  invalid_credentials: 'Usuário ou senha incorretos.',
  too_many_attempts: 'Muitas tentativas de acesso. Aguarde alguns instantes e tente novamente.',
  authentication_required: 'Sua sessão expirou. Entre novamente.',
  access_denied: 'Você não tem permissão para esta ação.',
  invalid_request: 'Os dados enviados são inválidos. Revise os campos destacados.',
  not_found: 'O recurso não foi encontrado.',
  unsupported_media_type: 'Formato de envio não suportado.',
  request_too_large: 'O conteúdo excede o tamanho máximo permitido.',
  project_limit: 'O limite de projetos foi atingido.',
  attachment_limit: 'Este projeto já atingiu o limite de anexos.',
  duplicate_attachment: 'Este documento já está anexado ao projeto.',
  pdf_invalid: 'O arquivo não é um PDF válido.',
  pdf_encrypted: 'PDFs criptografados ou protegidos por senha não são suportados.',
  pdf_too_many_pages: 'O PDF excede o limite de páginas.',
  pdf_no_text: 'O PDF não tem texto extraível. Documentos digitalizados (OCR) ainda não são suportados.',
  pdf_timeout: 'O PDF demorou demais para ser processado.',
  remote_disabled: 'Provedores remotos estão desativados neste servidor (DOCGEN_ALLOW_REMOTE).',
  consent_required: 'Este provedor envia dados para um serviço remoto. Revise e autorize a transmissão.',
  consent_invalid: 'A autorização de envio expirou ou é inválida. Revise a transmissão novamente.',
  consent_stale: 'O projeto mudou depois da revisão. Revise a transmissão novamente.',
  plan_not_approved: 'Aprove o plano arquitetural antes de gerar a documentação.',
  plan_state_changed: 'O plano foi alterado durante a aprovação. Recarregue a página.',
  too_many_jobs: 'Há muitas tarefas em andamento. Aguarde a conclusão de alguma delas.',
  queue_full: 'A fila de processamento está cheia. Tente novamente em instantes.',
  job_not_cancellable: 'A tarefa já foi concluída.',
  revision_conflict: 'O documento foi alterado em outro lugar. Recarregue antes de salvar.',
  unsafe_markdown: 'O documento precisa ser Markdown passivo (sem HTML, imagens ou scripts) e começar com um título H1.',
  artifact_read_only: 'Diagramas são gerados a partir do plano e não podem ser editados.',
  nothing_to_export: 'Gere um plano arquitetural antes de exportar.',
  too_many_sessions: 'Há sessões ativas demais no servidor.',
  invalid_host: 'Endereço de acesso não permitido.',
  pairing_unavailable: 'Para conectar este navegador, clique em “Abrir DocGen” na janela do aplicativo DocGen.',
  pairing_conflict: 'Outra solicitação de acesso apareceu ao mesmo tempo e, por segurança, todas foram canceladas. Clique em “Abrir DocGen” na janela do aplicativo para tentar de novo.',
  pairing_denied: 'O acesso deste navegador foi recusado na janela do DocGen.',
  pairing_expired: 'O código expirou antes da confirmação. Clique em “Abrir DocGen” na janela do aplicativo para gerar outro.',
  pairing_replaced: 'Esta solicitação foi substituída ou cancelada. Use a aba aberta mais recentemente pelo DocGen ou clique em “Abrir DocGen” de novo.',
  origin_rejected: 'Este endereço não pode solicitar acesso ao DocGen.',
  internal_error: 'Ocorreu um erro interno. Tente novamente.',
};

const JOB_ERRORS = {
  cancelled: 'A tarefa foi cancelada.',
  interrupted: 'O servidor foi reiniciado antes da conclusão da tarefa.',
  timeout: 'A tarefa excedeu o tempo limite.',
  consent_required: 'O envio remoto não foi autorizado.',
  invalid_model_output: 'O modelo não retornou um plano arquitetural válido. Tente novamente ou use outro modelo.',
  unsafe_model_output: 'A documentação gerada foi bloqueada pelas verificações de segurança de Markdown.',
  provider_timeout: 'O provedor de IA não respondeu a tempo.',
  provider_unavailable: 'O provedor de IA não está acessível. Para Ollama, confirme que o serviço está rodando em 127.0.0.1:11434.',
  provider_not_configured: 'O provedor de IA não está configurado no servidor (GROQ_API_KEY).',
  provider_rate_limited: 'O limite de uso do provedor de IA foi atingido. Aguarde e tente novamente.',
  provider_rejected: 'O provedor de IA recusou as credenciais configuradas no servidor.',
  provider_payload_too_large: 'O conteúdo é grande demais para o provedor. Reduza a descrição ou os anexos.',
  provider_failed: 'A requisição ao provedor de IA falhou.',
  internal_error: 'A tarefa falhou inesperadamente.',
};

export function errorMessage(error) {
  if (!error) return ERRORS.internal_error;
  if (error.name === 'AbortError') return 'Operação cancelada.';
  return ERRORS[error.code] || error.message || ERRORS.internal_error;
}

export function jobErrorMessage(jobError) {
  if (!jobError) return '';
  return JOB_ERRORS[jobError.code] || jobError.message || JOB_ERRORS.internal_error;
}

export const JOB_STATUS = {
  QUEUED: 'Na fila',
  RUNNING: 'Processando',
  SUCCEEDED: 'Concluído',
  FAILED: 'Falhou',
  CANCELLED: 'Cancelado',
};

export const JOB_TYPE = {
  ARCHITECTURE_PLAN: 'Planejamento arquitetural',
  PROJECT_DOCUMENTATION: 'Geração de documentação',
};

export const PLAN_STATUS = {
  DRAFT: 'Rascunho — aguardando revisão',
  APPROVED: 'Aprovado para documentação',
};

export const SOURCE_LABEL = {
  USER: 'Informado por você',
  DOCUMENT_INFERENCE: 'Inferido de anexo pela IA',
  AI_SUGGESTION: 'Sugestão da IA',
};

export const COMPONENT_TYPE = {
  CLIENT: 'Cliente',
  SERVICE: 'Serviço',
  API_GATEWAY: 'Gateway',
  WORKER: 'Worker',
  DATABASE: 'Banco de dados',
  CACHE: 'Cache',
  QUEUE: 'Fila',
  STORAGE: 'Armazenamento',
  EXTERNAL: 'Externo',
  OTHER: 'Outro',
};

export const IMPACT = { LOW: 'Baixo', MEDIUM: 'Médio', HIGH: 'Alto' };

export const INCLUDED_DATA = {
  project_brief: 'Nome, descrição, requisitos, tecnologias e restrições do projeto',
  attachment_text: 'Texto extraído dos PDFs anexados (com segredos redigidos)',
  approved_plan: 'Plano arquitetural aprovado',
};

export const ARTIFACT_LABEL = {
  README: 'README.md',
  ARCHITECTURE: 'ARCHITECTURE.md',
  DIAGRAM: 'Diagrama (Mermaid)',
};
