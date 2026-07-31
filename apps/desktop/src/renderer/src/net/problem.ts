import type { ProtocolError } from '@vaderai/protocol';

/**
 * What the user can do about a failure, which is the only distinction the
 * overlay needs to make.
 *
 * - `fatal` — the session is over until they act. Not dismissible.
 * - `transient` — the server is having trouble; the next question may work.
 * - `bug` — neither side can act on it, but hiding it would be worse.
 */
export type Severity = 'fatal' | 'transient' | 'bug';

/**
 * Written as a total record rather than a switch so that adding a code to the
 * protocol fails to compile here instead of silently falling through to a
 * default.
 */
const SEVERITY: Record<ProtocolError['code'], Severity> = {
  unauthorized: 'fatal',
  stt_failed: 'transient',
  llm_failed: 'transient',
  bad_request: 'bug',
  internal: 'bug',
};

export const severityOf = (code: ProtocolError['code']): Severity => SEVERITY[code];
