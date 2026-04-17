-- Programme reminders — alert the user when a scheduled show starts,
-- optionally auto-tuning to the channel.
--
-- A reminder is a snapshot of the EPG programme at the time the user sets it:
-- if the EPG refreshes later and the programme moves or disappears, the
-- reminder still fires based on the stored start_time. That's intentional —
-- otherwise EPG churn could silently drop reminders on the user.

CREATE TABLE IF NOT EXISTS reminders (
  id TEXT PRIMARY KEY,
  programme_id TEXT NOT NULL,
  channel_tvg_id TEXT NOT NULL,
  title TEXT NOT NULL,
  start_time INTEGER NOT NULL,
  end_time INTEGER NOT NULL,
  lead_seconds INTEGER NOT NULL DEFAULT 0,
  fire_at INTEGER NOT NULL,
  fired INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

-- Partial index keeps scans cheap: the scanner only asks for unfired rows.
CREATE INDEX IF NOT EXISTS idx_reminders_fire_at_unfired
  ON reminders(fire_at)
  WHERE fired = 0;

CREATE INDEX IF NOT EXISTS idx_reminders_programme
  ON reminders(programme_id);
