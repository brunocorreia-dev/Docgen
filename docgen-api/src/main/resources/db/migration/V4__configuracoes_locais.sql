-- Local preferences of the desktop app: the default model and whether the
-- first-run guide was completed. Values are short, server-validated strings;
-- no conversation content is ever stored here.
CREATE TABLE app_settings (
    owner         VARCHAR(64)              NOT NULL,
    setting_key   VARCHAR(64)              NOT NULL,
    setting_value VARCHAR(256)             NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (owner, setting_key)
);
