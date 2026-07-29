import { createClient, type SupabaseClient } from '@supabase/supabase-js';

const url = import.meta.env['VITE_SUPABASE_URL'] as string | undefined;
const anonKey = import.meta.env['VITE_SUPABASE_ANON_KEY'] as string | undefined;

/**
 * Null when the app was built without Supabase credentials — the overlay says so
 * rather than failing at sign-in with something cryptic.
 *
 * The anon key is publishable by design: it grants nothing on its own, and RLS
 * is what confines a signed-in user to their own rows. Provider secrets
 * (Deepgram, Anthropic) never reach this process.
 */
export const supabase: SupabaseClient | null =
  url && anonKey
    ? createClient(url, anonKey, {
        // Keeps the session in localStorage and refreshes it, so signing in
        // survives a restart.
        auth: { persistSession: true, autoRefreshToken: true },
      })
    : null;

export const serverWsUrl =
  (import.meta.env['VITE_SERVER_WS_URL'] as string | undefined) ?? 'ws://localhost:8787/v1/session';

/** Same backend, REST side. Derived from the socket URL unless set explicitly. */
export const serverHttpUrl =
  (import.meta.env['VITE_SERVER_HTTP_URL'] as string | undefined) ??
  serverWsUrl.replace(/^ws/, 'http').replace(/\/v1\/session$/, '');
