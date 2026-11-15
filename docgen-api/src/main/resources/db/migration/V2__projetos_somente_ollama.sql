-- DocGen 3 runs only on the local Ollama service. Projects that selected a
-- removed remote provider switch to Ollama; their model name belonged to that
-- provider, so it is cleared and the default local model applies.
-- Plans and jobs keep the provider they actually used: that is history.
UPDATE projects
SET provider = 'ollama',
    model = NULL
WHERE provider <> 'ollama';
