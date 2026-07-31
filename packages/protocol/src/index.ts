import { z } from 'zod';

/**
 * Wire format between the Electron desktop app and the Spring Boot backend.
 *
 * This file is the SOURCE OF TRUTH. The Java side mirrors it with records in
 * `ai.vader.server.protocol`. Fixtures in `/contracts/messages/*.json` are
 * asserted by both test suites, so a field rename on one side fails the other.
 *
 * Audio does NOT travel as JSON — it is sent as raw binary WebSocket frames
 * described by the AUDIO_* constants below.
 */

export const PROTOCOL_VERSION = 1;

// --- Audio frame format ----------------------------------------------------

export const AUDIO_SAMPLE_RATE = 16_000;
export const AUDIO_CHANNELS = 2;
export const AUDIO_FRAME_MS = 100;

/** 16000 * 0.1 * 2ch * 2 bytes = 6400. Spring's default binary buffer is 8192. */
export const AUDIO_FRAME_BYTES = (AUDIO_SAMPLE_RATE / 1000) * AUDIO_FRAME_MS * AUDIO_CHANNELS * 2;

/** Channel 0 carries system audio (the other party); channel 1 carries the mic. */
export const Channel = {
  Interviewer: 0,
  User: 1,
} as const;
export type Channel = (typeof Channel)[keyof typeof Channel];

export const channelSchema = z.union([z.literal(0), z.literal(1)]);

// --- Client -> Server ------------------------------------------------------

/**
 * First frame after connect. The WebSocket API cannot set an Authorization
 * header, so the token is carried here; the server closes 1008 if this does
 * not arrive with a valid token within 5 seconds.
 */
export const helloSchema = z.object({
  type: z.literal('hello'),
  protocolVersion: z.literal(PROTOCOL_VERSION),
  accessToken: z.string().min(1),
});

/** Ceiling on a typed question. Long enough to paste a problem statement. */
export const MAX_QUESTION_CHARS = 2000;

/**
 * Interview mode answers in the user's voice, out loud. Coding mode gives an
 * approach, then code, then complexity. Absent means interview, so a client
 * that predates this field still validates and PROTOCOL_VERSION stays 1.
 */
export const askModeSchema = z.enum(['interview', 'coding']);

/**
 * `question` is what the user typed in the ask bar. Absent means "answer the
 * interviewer's most recent question from the transcript", which is what the
 * auto-trigger and the bare Ctrl+Enter both do.
 */
export const askSchema = z.object({
  type: z.literal('ask'),
  trigger: z.enum(['manual', 'auto']),
  question: z.string().min(1).max(MAX_QUESTION_CHARS).optional(),
  mode: askModeSchema.optional(),
});

/**
 * Ceiling on an encoded screen grab: 256 KiB of base64, so 192 KiB of image.
 *
 * A screenshot travels as one WebSocket *text* frame, and the container caps
 * those — see `maxTextMessageBufferSize` in `WebSocketConfig`, which must stay
 * above this plus the JSON envelope. Exceeding the container limit closes the
 * connection with 1009 and no error frame, so the ceiling is enforced here, in
 * the main process before send, and again on the server: a screen that will
 * not fit has to come back as a sentence, not a dropped session.
 */
export const MAX_SCREENSHOT_BASE64_CHARS = 262_144;

export const screenshotSchema = z.object({
  type: z.literal('screenshot'),
  mimeType: z.union([z.literal('image/png'), z.literal('image/jpeg')]),
  dataBase64: z.string().min(1).max(MAX_SCREENSHOT_BASE64_CHARS),
  note: z.string().optional(),
});

export const pingSchema = z.object({
  type: z.literal('ping'),
});

export const clientMessageSchema = z.discriminatedUnion('type', [
  helloSchema,
  askSchema,
  screenshotSchema,
  pingSchema,
]);

export type Hello = z.infer<typeof helloSchema>;
export type AskMode = z.infer<typeof askModeSchema>;
export type Ask = z.infer<typeof askSchema>;
export type Screenshot = z.infer<typeof screenshotSchema>;
export type Ping = z.infer<typeof pingSchema>;
export type ClientMessage = z.infer<typeof clientMessageSchema>;

// --- Server -> Client ------------------------------------------------------

export const readySchema = z.object({
  type: z.literal('ready'),
  sessionId: z.string().uuid(),
});

export const transcriptSchema = z.object({
  type: z.literal('transcript'),
  channel: channelSchema,
  text: z.string(),
  isFinal: z.boolean(),
});

export const answerStartSchema = z.object({
  type: z.literal('answer_start'),
  answerId: z.string().uuid(),
});

export const answerDeltaSchema = z.object({
  type: z.literal('answer_delta'),
  answerId: z.string().uuid(),
  text: z.string(),
});

export const answerEndSchema = z.object({
  type: z.literal('answer_end'),
  answerId: z.string().uuid(),
});

export const errorSchema = z.object({
  type: z.literal('error'),
  code: z.enum([
    'unauthorized',
    'stt_failed',
    'llm_failed',
    'bad_request',
    'rate_limited',
    'internal',
  ]),
  message: z.string(),
});

export const pongSchema = z.object({
  type: z.literal('pong'),
});

export const serverMessageSchema = z.discriminatedUnion('type', [
  readySchema,
  transcriptSchema,
  answerStartSchema,
  answerDeltaSchema,
  answerEndSchema,
  errorSchema,
  pongSchema,
]);

export type Ready = z.infer<typeof readySchema>;
export type Transcript = z.infer<typeof transcriptSchema>;
export type AnswerStart = z.infer<typeof answerStartSchema>;
export type AnswerDelta = z.infer<typeof answerDeltaSchema>;
export type AnswerEnd = z.infer<typeof answerEndSchema>;
export type ProtocolError = z.infer<typeof errorSchema>;
export type Pong = z.infer<typeof pongSchema>;
export type ServerMessage = z.infer<typeof serverMessageSchema>;
