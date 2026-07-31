-- Phase 9b: the interview does not have to be in English.
--
-- One column on profiles rather than a preferences table. A language is the
-- only preference there is, and profiles already holds exactly one row per
-- user, created by the handle_new_user trigger — a second table would be a
-- join and a nullable row for no gain.
--
-- No check constraint: the valid set is whatever the speech model accepts, and
-- pinning it here would mean a migration every time that list moves. The
-- service layer validates against ai.vader.server.preferences.Language before
-- anything reaches the Deepgram URL, which is the boundary that actually
-- matters — this value is interpolated into a query string.

alter table public.profiles
  add column language text not null default 'en';

comment on column public.profiles.language is
  'Deepgram language code, or ''multi'' for code-switching. Validated in the service layer.';
