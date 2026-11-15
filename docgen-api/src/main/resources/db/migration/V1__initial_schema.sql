-- DocGen Web schema. Every row carries its owner; every query filters by it.
-- Raw PDF bytes, prompts and provider credentials are never stored.

CREATE TABLE projects (
    id                          CHAR(36)                 NOT NULL PRIMARY KEY,
    owner                       VARCHAR(64)              NOT NULL,
    name                        VARCHAR(120)             NOT NULL,
    description                 VARCHAR(8000)            NOT NULL,
    functional_requirements     CHARACTER LARGE OBJECT   NOT NULL,
    non_functional_requirements CHARACTER LARGE OBJECT   NOT NULL,
    preferred_technologies      CHARACTER LARGE OBJECT   NOT NULL,
    constraints_json            CHARACTER LARGE OBJECT   NOT NULL,
    provider                    VARCHAR(16)              NOT NULL,
    model                       VARCHAR(100),
    output_language             VARCHAR(8)               NOT NULL,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX projects_owner_idx ON projects (owner, updated_at);

-- Redacted, normalized and bounded text extracted from an uploaded PDF.
-- Rows expire (expires_at) and are purged by the retention job.
CREATE TABLE attachments (
    id              CHAR(36)                 NOT NULL PRIMARY KEY,
    project_id      CHAR(36)                 NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    owner           VARCHAR(64)              NOT NULL,
    display_name    VARCHAR(120)             NOT NULL,
    size_bytes      BIGINT                   NOT NULL,
    page_count      INT                      NOT NULL,
    character_count INT                      NOT NULL,
    truncated       BOOLEAN                  NOT NULL,
    sha256          CHAR(64)                 NOT NULL,
    extracted_text  CHARACTER LARGE OBJECT   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX attachments_project_idx ON attachments (project_id, owner);
CREATE INDEX attachments_expiry_idx ON attachments (expires_at);

CREATE TABLE plans (
    id            CHAR(36)                 NOT NULL PRIMARY KEY,
    project_id    CHAR(36)                 NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    owner         VARCHAR(64)              NOT NULL,
    status        VARCHAR(16)              NOT NULL,
    provider      VARCHAR(16)              NOT NULL,
    model         VARCHAR(100),
    plan_json     CHARACTER LARGE OBJECT   NOT NULL,
    mermaid       CHARACTER LARGE OBJECT   NOT NULL,
    warnings_json CHARACTER LARGE OBJECT   NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    approved_at   TIMESTAMP WITH TIME ZONE
);
CREATE INDEX plans_project_idx ON plans (project_id, owner, created_at);

CREATE TABLE artifacts (
    id         CHAR(36)                 NOT NULL PRIMARY KEY,
    project_id CHAR(36)                 NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    owner      VARCHAR(64)              NOT NULL,
    plan_id    CHAR(36)                 NOT NULL REFERENCES plans (id) ON DELETE CASCADE,
    kind       VARCHAR(16)              NOT NULL,
    file_name  VARCHAR(64)              NOT NULL,
    content    CHARACTER LARGE OBJECT   NOT NULL,
    revision   INT                      NOT NULL,
    edited     BOOLEAN                  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX artifacts_project_idx ON artifacts (project_id, owner, created_at);

CREATE TABLE jobs (
    id            CHAR(36)                 NOT NULL PRIMARY KEY,
    owner         VARCHAR(64)              NOT NULL,
    project_id    CHAR(36)                 NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    type          VARCHAR(24)              NOT NULL,
    status        VARCHAR(16)              NOT NULL,
    provider      VARCHAR(16)              NOT NULL,
    model         VARCHAR(100),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at    TIMESTAMP WITH TIME ZONE,
    finished_at   TIMESTAMP WITH TIME ZONE,
    result_id     CHAR(36),
    error_code    VARCHAR(48),
    error_message VARCHAR(300)
);
CREATE INDEX jobs_owner_idx ON jobs (owner, status);
CREATE INDEX jobs_project_idx ON jobs (project_id, owner, created_at);
