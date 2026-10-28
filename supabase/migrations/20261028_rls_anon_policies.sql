-- Allow the anon key (used by the Android agent directly) to manage device rows.
-- Without these policies, RLS blocks all anon writes and the agent's direct
-- Supabase persistence fails with 401.

ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE device_apps ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "anon manage devices" ON devices;
CREATE POLICY "anon manage devices" ON devices
    FOR ALL TO anon USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "anon manage device_apps" ON device_apps;
CREATE POLICY "anon manage device_apps" ON device_apps
    FOR ALL TO anon USING (true) WITH CHECK (true);
