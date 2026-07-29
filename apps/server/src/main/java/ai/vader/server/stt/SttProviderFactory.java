package ai.vader.server.stt;

/** One provider per session — implementations hold per-connection socket state. */
public interface SttProviderFactory {

    SttProvider create();
}
