package ai.vader.server.config;

import ai.vader.server.knowledge.KnowledgeKind;
import ai.vader.server.persistence.AnswerTrigger;
import ai.vader.server.persistence.SessionKind;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

/**
 * Spring Data JDBC would persist an enum by its Java name — {@code JOB_DESCRIPTION} —
 * which the {@code kind} check constraint on {@code knowledge_docs} rejects. These
 * converters write the wire name the schema actually expects.
 *
 * <p>Every enum column needs its pair added to the single {@link JdbcCustomConversions}
 * bean below. A second bean of that type would clash rather than merge.
 */
@Configuration
public class JdbcConversionsConfig {

    @WritingConverter
    enum KnowledgeKindToString implements Converter<KnowledgeKind, String> {
        INSTANCE;

        @Override
        public String convert(KnowledgeKind source) {
            return source.wireName();
        }
    }

    @ReadingConverter
    enum StringToKnowledgeKind implements Converter<String, KnowledgeKind> {
        INSTANCE;

        @Override
        public KnowledgeKind convert(String source) {
            return KnowledgeKind.fromWireName(source);
        }
    }

    @WritingConverter
    enum SessionKindToString implements Converter<SessionKind, String> {
        INSTANCE;

        @Override
        public String convert(SessionKind source) {
            return source.wireName();
        }
    }

    @ReadingConverter
    enum StringToSessionKind implements Converter<String, SessionKind> {
        INSTANCE;

        @Override
        public SessionKind convert(String source) {
            return SessionKind.fromWireName(source);
        }
    }

    @WritingConverter
    enum AnswerTriggerToString implements Converter<AnswerTrigger, String> {
        INSTANCE;

        @Override
        public String convert(AnswerTrigger source) {
            return source.wireName();
        }
    }

    @ReadingConverter
    enum StringToAnswerTrigger implements Converter<String, AnswerTrigger> {
        INSTANCE;

        @Override
        public AnswerTrigger convert(String source) {
            return AnswerTrigger.fromWireName(source);
        }
    }

    @Bean
    JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(
                KnowledgeKindToString.INSTANCE,
                StringToKnowledgeKind.INSTANCE,
                SessionKindToString.INSTANCE,
                StringToSessionKind.INSTANCE,
                AnswerTriggerToString.INSTANCE,
                StringToAnswerTrigger.INSTANCE));
    }
}
