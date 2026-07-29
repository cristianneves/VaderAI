package ai.vader.server.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    private static byte[] pdfContaining(String... lines) throws Exception {
        try (var document = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16f);
                content.newLineAtOffset(40, 700);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docxContaining(String... paragraphs) throws Exception {
        try (var document = new XWPFDocument();
                var out = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                document.createParagraph().createRun().setText(text);
            }
            document.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsTextFromAPdf() throws Exception {
        byte[] pdf = pdfContaining("Cristian Neves", "Cut deploy time from 40 to 6 minutes");

        String text = extractor.extract(DocumentTextExtractor.PDF, pdf);

        assertThat(text).contains("Cristian Neves").contains("Cut deploy time from 40 to 6 minutes");
    }

    @Test
    void extractsTextFromADocx() throws Exception {
        byte[] docx = docxContaining("Senior Engineer", "Led the migration to Postgres");

        String text = extractor.extract(DocumentTextExtractor.DOCX, docx);

        assertThat(text).contains("Senior Engineer").contains("Led the migration to Postgres");
    }

    @Test
    void passesPlainTextThrough() throws Exception {
        String text = extractor.extract("text/plain", "Just some notes".getBytes(StandardCharsets.UTF_8));

        assertThat(text).isEqualTo("Just some notes");
    }

    @Test
    void toleratesACharsetOnTheContentType() throws Exception {
        String text = extractor.extract("text/plain; charset=utf-8", "notes".getBytes(StandardCharsets.UTF_8));

        assertThat(text).isEqualTo("notes");
    }

    @Test
    void rejectsAnUnsupportedType() {
        assertThatThrownBy(() -> extractor.extract("image/png", new byte[] {1, 2, 3}))
                .isInstanceOf(DocumentTextExtractor.UnsupportedDocumentException.class)
                .hasMessageContaining("image/png");
    }

    @Test
    void failsOnAFileThatIsNotThePdfItClaimsToBe() {
        assertThatThrownBy(() -> extractor.extract(DocumentTextExtractor.PDF, "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void collapsesRaggedWhitespace() throws Exception {
        byte[] raw = "Line   one\r\n\r\n\r\n\r\nLine    two   ".getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract("text/plain", raw);

        assertThat(text).isEqualTo("Line one\n\nLine two");
    }

    @Test
    void producesTheSameBytesForTheSameFile() throws Exception {
        // Re-uploading an unchanged résumé must not change the prompt prefix, or
        // the cache is thrown away for nothing.
        byte[] pdf = pdfContaining("Cristian Neves", "Postgres migration");

        assertThat(extractor.extract(DocumentTextExtractor.PDF, pdf))
                .isEqualTo(extractor.extract(DocumentTextExtractor.PDF, pdf));
    }
}
