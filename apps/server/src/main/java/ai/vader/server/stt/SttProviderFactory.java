package ai.vader.server.stt;

/** One provider per session — implementations hold per-connection socket state. */
public interface SttProviderFactory {

    /**
     * @param languageCode the speech model's language parameter. A plain string
     *     rather than the {@code preferences.Language} enum so the speech layer
     *     stays free of anything that knows about users; the caller has already
     *     validated it against the allow-list.
     */
    SttProvider create(String languageCode);
}
