-- Practice mode: mock interviews with graded feedback, no live call.
--
-- A practice run reuses the ordinary session pipeline — same WebSocket, same
-- STT, same transcript_turns rows — so it gets a session row like any other and
-- this column is what tells the two apart. Session history (Phase 7) can then
-- label a run without inspecting its child rows.
alter table public.sessions
    add column kind text not null default 'live' check (kind in ('live', 'practice'));

create table public.practice_questions (
    id bigint generated always as identity primary key,
    session_id uuid not null references public.sessions (id) on delete cascade,
    -- Denormalized from sessions for the same reason as transcript_turns: both
    -- the RLS policy and the service-layer scoping check stay single-column
    -- predicates with no join.
    user_id uuid not null references auth.users (id) on delete cascade,
    -- 0-based, in the order the questions are asked.
    position smallint not null,
    question text not null,
    -- Everything below is null until the answer is graded.
    answer text,
    -- The three axes the answer is graded on, 1 (weak) to 5 (strong). Separate
    -- columns rather than one overall score so the session report can name the
    -- weakest theme from data instead of re-reading the feedback prose.
    structure smallint check (structure between 1 and 5),
    specificity smallint check (specificity between 1 and 5),
    relevance smallint check (relevance between 1 and 5),
    feedback text,
    rewrite text,
    created_at timestamptz not null default now(),
    graded_at timestamptz
);

-- One question per slot per session. Makes the grade write an addressable upsert
-- and stops a retried start from doubling the question set.
create unique index practice_questions_session_id_position_idx
    on public.practice_questions (session_id, position);

alter table public.practice_questions enable row level security;

create policy practice_questions_own_rows on public.practice_questions
    for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
