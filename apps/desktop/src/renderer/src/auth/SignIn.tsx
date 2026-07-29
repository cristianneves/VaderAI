import { useState } from 'react';
import { supabase } from './supabase';

export function SignIn(): React.JSX.Element {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(mode: 'in' | 'up'): Promise<void> {
    if (supabase === null) return;
    setBusy(true);
    setError(null);
    const { error: failed } =
      mode === 'in'
        ? await supabase.auth.signInWithPassword({ email, password })
        : await supabase.auth.signUp({ email, password });
    if (failed) setError(failed.message);
    setBusy(false);
  }

  if (supabase === null) {
    return (
      <section className="pane">
        <h2>Sign in</h2>
        <p className="empty">
          Not configured. Set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY, then restart.
        </p>
      </section>
    );
  }

  return (
    <section className="pane signin">
      <h2>Sign in</h2>
      <input
        type="email"
        placeholder="email"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
      />
      <input
        type="password"
        placeholder="password"
        value={password}
        onChange={(event) => setPassword(event.target.value)}
      />
      <div className="row">
        <button disabled={busy} onClick={() => void submit('in')}>
          Sign in
        </button>
        <button disabled={busy} onClick={() => void submit('up')}>
          Create account
        </button>
      </div>
      {error !== null && <p className="empty">{error}</p>}
    </section>
  );
}
