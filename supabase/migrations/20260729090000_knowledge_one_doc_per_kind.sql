-- The settings screen has exactly three slots — résumé, job description, notes —
-- so a user has at most one document of each kind. Enforcing it here means the
-- upsert is a single statement and cannot race itself into duplicates.
create unique index knowledge_docs_user_id_kind_idx on public.knowledge_docs (user_id, kind);
