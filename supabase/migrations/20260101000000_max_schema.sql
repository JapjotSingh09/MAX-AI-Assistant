-- MAX schema for the SUPABASE deployment variant.
-- Run with: supabase db push   (or paste into the Supabase SQL editor)
-- Every user-owned table has Row Level Security: user_id = auth.uid().
-- Even if a client bug or a leaked anon key tries to read someone else's rows,
-- the database itself refuses.

create extension if not exists "pgcrypto";

-- ---------- helpers ----------
create or replace function public.set_updated_at() returns trigger as $$
begin new.updated_at = now(); return new; end; $$ language plpgsql;

-- ---------- profiles ----------
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  full_name text,
  avatar_url text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Auto-create a profile + preferences row when a user signs up.
create or replace function public.handle_new_user() returns trigger as $$
begin
  insert into public.profiles (id, full_name) values (new.id, coalesce(new.raw_user_meta_data->>'full_name', split_part(new.email, '@', 1)));
  insert into public.user_preferences (user_id) values (new.id);
  return new;
end; $$ language plpgsql security definer set search_path = public;

-- ---------- user_preferences ----------
create table public.user_preferences (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null unique references auth.users(id) on delete cascade,
  theme text not null default 'dark',
  voice_enabled boolean not null default true,
  voice_name text,
  assistant_name text not null default 'MAX',
  language text not null default 'en-US',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------- conversations / messages ----------
create table public.conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null default 'New conversation',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index conversations_user_updated_idx on public.conversations (user_id, updated_at desc);

create table public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('user', 'assistant')),
  content text not null,
  created_at timestamptz not null default now()
);
create index messages_conversation_created_idx on public.messages (conversation_id, created_at desc);
create index messages_user_created_idx on public.messages (user_id, created_at desc);

-- ---------- assistant_actions ----------
create table public.assistant_actions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  conversation_id uuid references public.conversations(id) on delete set null,
  action_type text not null,
  action_payload jsonb not null default '{}',
  status text not null,
  error_message text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index assistant_actions_user_created_idx on public.assistant_actions (user_id, created_at desc);

-- ---------- automations ----------
create table public.automations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null,
  trigger_type text not null,
  trigger_config jsonb not null default '{}',
  action_type text not null,
  action_config jsonb not null default '{}',
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index automations_user_created_idx on public.automations (user_id, created_at desc);

-- ---------- activity_logs ----------
create table public.activity_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  action_type text not null,
  metadata jsonb not null default '{}',
  created_at timestamptz not null default now()
);
-- Supports cursor pagination: WHERE user_id = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC
create index activity_logs_user_created_idx on public.activity_logs (user_id, created_at desc, id desc);

-- ---------- usage_events ----------
create table public.usage_events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  event_type text not null,
  tokens_used integer not null default 0,
  provider text,
  model text,
  request_count integer not null default 1,
  latency_ms integer,
  success boolean not null default true,
  error_category text,
  created_at timestamptz not null default now()
);
create index usage_events_user_created_idx on public.usage_events (user_id, created_at desc);

-- ---------- devices ----------
create table public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  device_name text not null,
  platform text not null,
  app_version text,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);
create index devices_user_idx on public.devices (user_id);

-- ---------- memories ----------
create table public.memories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  content text not null check (char_length(content) <= 300),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index memories_user_created_idx on public.memories (user_id, created_at desc);

-- ---------- rate limits (server only) ----------
create table public.rate_limits (
  key text not null,
  window_start timestamptz not null,
  count integer not null default 0,
  primary key (key, window_start)
);
create index rate_limits_window_idx on public.rate_limits (window_start);

-- ---------- updated_at triggers ----------
do $$
declare t text;
begin
  foreach t in array array['profiles','user_preferences','conversations','assistant_actions','automations','memories'] loop
    execute format('create trigger %I_updated_at before update on public.%I for each row execute function public.set_updated_at()', t, t);
  end loop;
end $$;

-- ---------- ROW LEVEL SECURITY ----------
-- profiles is keyed by id; every other table by user_id.
alter table public.profiles enable row level security;
create policy "own profile select" on public.profiles for select using (id = auth.uid());
create policy "own profile update" on public.profiles for update using (id = auth.uid()) with check (id = auth.uid());
create policy "own profile insert" on public.profiles for insert with check (id = auth.uid());

do $$
declare t text;
begin
  foreach t in array array['user_preferences','conversations','messages','assistant_actions','automations','activity_logs','usage_events','devices','memories'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy "own rows select" on public.%I for select using (user_id = auth.uid())', t);
    execute format('create policy "own rows insert" on public.%I for insert with check (user_id = auth.uid())', t);
    execute format('create policy "own rows update" on public.%I for update using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
    execute format('create policy "own rows delete" on public.%I for delete using (user_id = auth.uid())', t);
  end loop;
end $$;

-- Extra guard: a message can only be added to a conversation the user owns.
drop policy "own rows insert" on public.messages;
create policy "own messages insert" on public.messages for insert
  with check (user_id = auth.uid() and exists (select 1 from public.conversations c where c.id = conversation_id and c.user_id = auth.uid()));

-- usage_events are written by the backend (service role) only; users may read their own.
drop policy "own rows insert" on public.usage_events;
drop policy "own rows update" on public.usage_events;
drop policy "own rows delete" on public.usage_events;

-- rate_limits: RLS on with NO policies = invisible to clients; only the service role can use it.
alter table public.rate_limits enable row level security;
