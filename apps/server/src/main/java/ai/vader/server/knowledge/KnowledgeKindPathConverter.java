package ai.vader.server.knowledge;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Path variables bind enums with {@code Enum.valueOf}, which expects
 * {@code JOB_DESCRIPTION}. The URLs use the wire name — {@code /v1/knowledge/job_description} —
 * so the conversion has to be spelled out or every write 400s.
 */
@Component
class KnowledgeKindPathConverter implements Converter<String, KnowledgeKind> {

    @Override
    public KnowledgeKind convert(String source) {
        return KnowledgeKind.fromWireName(source);
    }
}
