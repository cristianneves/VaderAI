-- Session history: the answers that were given, so a session can be reviewed
-- after the call ends.
--
-- Answers could not live in transcript_turns: its channel column is constrained
-- to (0, 1) because it mirrors Deepgram's channel index — interviewer and mic.
-- An assistant turn is a different thing with a different origin, and widening
-- that constraint would blur the speaker attribution the whole pipeline rests on.
create table public.answers (
    id bigint generated always as identity primary key,
    session_id uuid not null references public.sessions (id) on delete cascade,
    -- Denormalized from sessions for the same reason as transcript_turns: both
    -- the RLS policy and the service-layer scoping check stay single-column
    -- predicates with no join.
    user_id uuid not null references auth.users (id) on delete cascade,
    content text not null,
    -- What prompted it. A screenshot answer has no transcript question sitting
    -- before it, so without this the review screen would show it floating with
    -- nothing to pair against.
    trigger text not null check (trigger in ('auto', 'manual', 'screenshot')),
    created_at timestamptz not null default now()
);

-- Reviewing a session reads its answers in the order they were given, which is
-- id order — created_at can tie when two answers land in the same millisecond.
create index answers_session_id_id_idx on public.answers (session_id, id);

alter table public.answers enable row level security;

create policy answers_own_rows on public.answers
    for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
