ALTER TABLE fg_tenants ADD COLUMN prototype_expires_at timestamptz;
ALTER TABLE fg_tenants ADD COLUMN prototype_actions integer NOT NULL DEFAULT 0;
CREATE TABLE fg_prototype_quota (id integer PRIMARY KEY, total integer NOT NULL DEFAULT 0, window_start timestamptz NOT NULL DEFAULT now(), window_count integer NOT NULL DEFAULT 0);
INSERT INTO fg_prototype_quota(id) VALUES(1);
