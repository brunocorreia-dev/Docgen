// User-facing text (pt-BR). The API returns stable error codes with English
// messages; the workspace translates the codes it knows.

const ERRORS = {
  network_error: 'Não foi possível falar com o DocGen. Ele pode ter sido encerrado: abra-o de novo pelo menu ou pelo atalho.',
  authentication_required: 'Sua sessão terminou. Abra o DocGen de novo pelo menu ou pelo atalho.',
  launch_token_invalid: 'Este link de acesso expirou ou já foi usado. Abra o DocGen de novo pelo menu ou pelo atalho.',
  launch_token_reused: 'Este link de acesso já tinha sido usado por outro programa. Por segurança, a sessão foi encerrada.',
  origin_rejected: 'A requisição veio de uma página que não é o DocGen e foi bloqueada.',
  desktop_unavailable: 'Esta ação só existe no aplicativo DocGen para desktop.',
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
  plan_not_approved: 'Aprove o plano arquitetural antes de gerar a documentação.',
  plan_state_changed: 'O plano foi alterado durante a aprovação. Recarregue a página.',
  too_many_jobs: 'Há muitas tarefas em andamento. Aguarde a conclusão de alguma delas.',
  queue_full: 'A fila de processamento está cheia. Tente novamente em instantes.',
  job_not_cancellable: 'A tarefa já foi concluída.',
  revision_conflict: 'O documento foi alterado em outro lugar. Recarregue antes de salvar.',
  unsafe_markdown: 'O documento precisa ser Markdown passivo (sem HTML, imagens ou scripts) e começar com um título H1.',
  artifact_read_only: 'Diagramas são gerados a partir do plano e não podem ser editados.',
  nothing_to_export: 'Gere um plano arquitetural antes de exportar.',
  invalid_host: 'Endereço de acesso não permitido.',
  internal_error: 'Ocorreu um erro interno. Tente novamente.',
};

const JOB_ERRORS = {
  cancelled: 'A tarefa foi cancelada.',
  interrupted: 'O servidor foi reiniciado antes da conclusão da tarefa.',
  timeout: 'A tarefa excedeu o tempo limite.',
  invalid_model_output: 'O modelo não retornou um plano arquitetural válido. Tente novamente ou use outro modelo.',
  unsafe_model_output: 'A documentação gerada foi bloqueada pelas verificações de segurança de Markdown.',
  provider_timeout: 'O Ollama não respondeu a tempo. Modelos grandes podem demorar no primeiro uso; tente novamente.',
  provider_unavailable: 'O Ollama não está acessível neste computador. Abra o Ollama e tente novamente.',
  provider_failed: 'A requisição ao Ollama falhou. Tente novamente.',
  model_not_installed: 'O modelo escolhido não está instalado no Ollama. Instale-o pelo Ollama ou escolha outro modelo no projeto. O DocGen não baixa modelos sozinho.',
  prompt_too_large: 'O conteúdo não cabe na janela de contexto do modelo. Reduza a descrição ou os anexos, ou escolha um modelo com contexto maior.',
  prompt_truncated: 'O Ollama descartou parte do conteúdo, então o resultado foi descartado por segurança. Atualize o Ollama e tente novamente.',
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

export const ARTIFACT_LABEL = {
  README: 'README.md',
  ARCHITECTURE: 'ARCHITECTURE.md',
  DIAGRAM: 'Diagrama (Mermaid)',
};
