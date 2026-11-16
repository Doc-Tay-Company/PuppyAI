package com.puppy.document;

import com.puppy.model.DocumentAttachment;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorTest {

    @TempDir
    Path tempDirectory;

    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsPlainTextAndMarkdown() throws Exception {
        Path file = tempDirectory.resolve("notes.md");
        Files.writeString(file, "# Puppy notes\n\nRead the document before answering.", StandardCharsets.UTF_8);

        DocumentAttachment attachment = extractor.extract(file);

        assertEquals("notes.md", attachment.fileName());
        assertEquals("md", attachment.extension());
        assertTrue(attachment.text().contains("Read the document before answering."));
        assertEquals(0, attachment.pageCount());
    }

    @Test
    void extractsDocxParagraphsAndTables() throws Exception {
        Path file = tempDirectory.resolve("study-notes.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("Puppy studies DOCX files.");
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Topic");
            table.getRow(0).getCell(1).setText("Puppy");
            document.write(output);
        }

        DocumentAttachment attachment = extractor.extract(file);

        assertTrue(attachment.text().contains("Puppy studies DOCX files."));
        assertTrue(attachment.text().contains("Topic | Puppy"));
        assertEquals(0, attachment.pageCount());
    }

    @Test
    void extractsPdfTextWithPageMarkers() throws Exception {
        Path file = tempDirectory.resolve("puppy-guide.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Puppy reads PDF files.");
                content.endText();
            }
            document.save(file.toFile());
        }

        DocumentAttachment attachment = extractor.extract(file);

        assertTrue(attachment.text().contains("--- Page 1 ---"));
        assertTrue(attachment.text().contains("Puppy reads PDF files."));
        assertEquals(1, attachment.pageCount());
    }

    @Test
    void rejectsPdfWithoutSelectableText() throws Exception {
        Path file = tempDirectory.resolve("scanned.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(file.toFile());
        }

        DocumentExtractionException error =
                assertThrows(DocumentExtractionException.class, () -> extractor.extract(file));

        assertTrue(error.getMessage().contains("Scanned"));
    }

    @Test
    void truncatesLongDocuments() throws Exception {
        Path file = tempDirectory.resolve("long.txt");
        Files.writeString(file, "a".repeat(DocumentTextExtractor.MAX_CHARS + 5_000));

        DocumentAttachment attachment = extractor.extract(file);

        assertTrue(attachment.truncated());
        assertTrue(attachment.text().length() <= DocumentTextExtractor.MAX_CHARS);
        assertTrue(attachment.text().contains("document truncated"));
    }

    @Test
    void rejectsEmptyAndUnsupportedFiles() throws Exception {
        Path empty = tempDirectory.resolve("empty.txt");
        Files.writeString(empty, "   ");
        DocumentExtractionException emptyError =
                assertThrows(DocumentExtractionException.class, () -> extractor.extract(empty));
        assertTrue(emptyError.getMessage().contains("empty"));

        Path unsupported = tempDirectory.resolve("picture.png");
        Files.write(unsupported, new byte[]{1, 2, 3});
        DocumentExtractionException unsupportedError =
                assertThrows(DocumentExtractionException.class, () -> extractor.extract(unsupported));
        assertTrue(unsupportedError.getMessage().contains("Unsupported file type"));
    }

    @Test
    void rejectsMissingFiles() {
        DocumentExtractionException error = assertThrows(DocumentExtractionException.class,
                () -> extractor.extract(tempDirectory.resolve("missing.pdf")));

        assertTrue(error.getMessage().contains("couldn't find"));
    }
}
