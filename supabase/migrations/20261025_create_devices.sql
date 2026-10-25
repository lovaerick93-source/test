-- Table to store registered mobile devices
CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    name TEXT,
    model TEXT,
    manufacturer TEXT,
    android_version TEXT,
    sdk_int INTEGER,
    screen_width INTEGER,
    screen_height INTEGER,
    battery_percent INTEGER,
    can_capture BOOLEAN,
    can_inject_input BOOLEAN,
    installed_app_count INTEGER,
    pairing_code TEXT,
    auto_approve BOOLEAN DEFAULT FALSE,
    last_ip TEXT,
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Table to store installed apps per device
CREATE TABLE IF NOT EXISTS device_apps (
    device_id TEXT REFERENCES devices(device_id) ON DELETE CASCADE,
    package_name TEXT,
    label TEXT,
    is_system BOOLEAN DEFAULT FALSE,
    PRIMARY KEY (device_id, package_name)
);

-- Index for faster lookups by pairing code
CREATE INDEX IF NOT EXISTS idx_devices_pairing_code ON devices(pairing_code);
