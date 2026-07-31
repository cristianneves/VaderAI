-- Phase 9d: post-call notes.
--
-- Stored rather than regenerated. The practice report route recomputes its
-- themes on every view and docs/002 already calls that out as re-billing a
-- model call to read a page; this is the same shape and does not repeat it.
-- One row per session, so reopening a recap costs a select.
--
-- key_points and action_items are text[] rather than jsonb or child tables.
-- Nothing ever queries inside them — they are rendered as two bullet lists —
-- and Spring Data JDBC maps a Postgres array to String[] with no custom
-- converter, where jsonb would need a PGobject pair in JdbcConversionsConfig.

create table public.session_summaries (
  session_id   uuid primary key references public.sessions (id) on delete cascade,
  user_id      uuid not null references auth.users (id) on delete cascade,
  summary      text not null,
  key_points   text[] not null default '{}',
  action_items text[] not null default '{}',
  created_at   timestamptz not null default now()
);

create index session_summaries_user_id_idx on public.session_summaries (user_id);

alter table public.session_summaries enable row level security;

create policy own_rows on public.session_summaries
  for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
