-- DocGen 3 has no user accounts: the desktop app has one fixed owner, 'local'.
-- The owner column stays, and every query keeps filtering by it, as a second
-- line of defense. Rows created under former login names move to 'local'.
UPDATE projects SET owner = 'local' WHERE owner <> 'local';
UPDATE attachments SET owner = 'local' WHERE owner <> 'local';
UPDATE plans SET owner = 'local' WHERE owner <> 'local';
UPDATE artifacts SET owner = 'local' WHERE owner <> 'local';
UPDATE jobs SET owner = 'local' WHERE owner <> 'local';
