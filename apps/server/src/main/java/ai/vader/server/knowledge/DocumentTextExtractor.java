package ai.vader.server.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/**
 * Turns an uploaded résumé into plain text. Extraction happens here rather than
 * in the desktop app so the parsers stay on one platform and one Java version.
 */
@Component
public class DocumentTextExtractor {

    public static final String PDF = "application/pdf";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Rejected rather than guessed: a mislabelled file would store binary noise as a prompt. */
    public static class UnsupportedDocumentException extends RuntimeException {
        UnsupportedDocumentException(String message) {
            super(message);
        }
    }

    public String extract(String contentType, byte[] bytes) throws IOException {
        String type = contentType == null ? "" : contentType.toLowerCase().split(";")[0].trim();
        return switch (type) {
            case PDF -> normalize(fromPdf(bytes));
            case DOCX -> normalize(fromDocx(bytes));
            case "text/plain", "text/markdown", "" -> normalize(new String(bytes, StandardCharsets.UTF_8));
            default -> throw new UnsupportedDocumentException("unsupported document type: " + type);
        };
    }

    private String fromPdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String fromDocx(byte[] bytes) throws IOException {
        try (InputStream in = new ByteArrayInputStream(bytes);
                var document = new XWPFDocument(in);
                var extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * Collapses the ragged whitespace PDF extraction produces. This also keeps
     * the prompt prefix stable: re-uploading the same file must yield the same
     * bytes or the cache is thrown away.
     */
    private String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \t]+", " ")
                .replaceAll(" ?\n ?", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
