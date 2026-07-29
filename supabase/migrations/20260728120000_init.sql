-- VaderAI core schema.
--
-- RLS is on every table so the client-direct path (supabase-js in the desktop
-- app) can only ever see its own rows. The Java backend connects with the
-- service role, which BYPASSES RLS entirely — every query it makes must be
-- scoped by the user id taken from the verified JWT. These policies do not
-- protect that path; the service layer does.

create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    email text,
    created_at timestamptz not null default now()
);

create table public.sessions (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    started_at timestamptz not null default now(),
    ended_at timestamptz
);

create index sessions_user_id_started_at_idx on public.sessions (user_id, started_at desc);

create table public.transcript_turns (
    id bigint generated always as identity primary key,
    session_id uuid not null references public.sessions (id) on delete cascade,
    -- Denormalized from sessions so both the RLS policy and the service-layer
    -- scoping check are a single-column predicate with no join.
    user_id uuid not null references auth.users (id) on delete cascade,
    -- 0 = interviewer (system audio), 1 = user (mic). Mirrors Channel in
    -- packages/protocol.
    channel smallint not null check (channel in (0, 1)),
    content text not null,
    created_at timestamptz not null default now()
);

create index transcript_turns_session_id_id_idx on public.transcript_turns (session_id, id);

create table public.knowledge_docs (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    kind text not null check (kind in ('resume', 'job_description', 'notes')),
    title text,
    content text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index knowledge_docs_user_id_idx on public.knowledge_docs (user_id);

alter table public.profiles enable row level security;
alter table public.sessions enable row level security;
alter table public.transcript_turns enable row level security;
alter table public.knowledge_docs enable row level security;

create policy profiles_own_row on public.profiles
    for all to authenticated using ((select auth.uid()) = id) with check ((select auth.uid()) = id);

create policy sessions_own_rows on public.sessions
    for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create policy transcript_turns_own_rows on public.transcript_turns
    for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create policy knowledge_docs_own_rows on public.knowledge_docs
    for all to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Sign-up creates the auth.users row; without this the profile would never exist.
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.profiles (id, email) values (new.id, new.email);
    return new;
end;
$$;

create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_user();
