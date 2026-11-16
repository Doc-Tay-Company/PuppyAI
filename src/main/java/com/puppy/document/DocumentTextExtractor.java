package com.puppy.document;

import com.puppy.model.DocumentAttachment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Extracts plain text from the document formats Puppy can read today.
 *
 * <p>PDF pages keep a {@code --- Page N ---} marker so the model can cite a
 * page. DOCX tables are flattened into pipe-separated rows. Scanned PDFs have
 * no selectable text and are rejected with a friendly message. Only the text is
 * kept in memory; the file itself is never modified or copied.</p>
 */
public final class DocumentTextExtractor {

    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_CHARS = 80_000;

    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("pdf", "docx", "doc", "txt", "md");
    private static final String TRUNCATION_NOTICE =
            "\n\n[... document truncated to fit Puppy's context ...]";

    /** Extracts text from a supported local file. Safe to call off the FX thread. */
    public DocumentAttachment extract(Path file) throws DocumentExtractionException {
        Objects.requireNonNull(file, "file");
        Path source = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new DocumentExtractionException("Puppy couldn't find that file.");
        }

        long size;
        try {
            size = Files.size(source);
        } catch (IOException e) {
            throw new DocumentExtractionException("Puppy couldn't open that file.", e);
        }
        if (size > MAX_FILE_BYTES) {
            throw new DocumentExtractionException(
                    "That file is too large to attach. Please choose one smaller than 20 MB.");
        }

        String extension = extensionOf(source);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new DocumentExtractionException(
                    "Unsupported file type. Attach a PDF, Word document (.docx/.doc), TXT or Markdown file.");
        }

        try {
            return switch (extension) {
                case "pdf" -> extractPdf(source);
                case "docx" -> extractDocx(source);
                case "doc" -> extractDoc(source);
                default -> extractPlainText(source);
            };
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentExtractionException(
                    "Puppy couldn't read \"%s\". It may be damaged, encrypted or in an unsupported format."
                            .formatted(source.getFileName()), e);
        } catch (RuntimeException e) {
            throw new DocumentExtractionException(
                    "Puppy couldn't read \"%s\". It may be damaged or in an unsupported format."
                            .formatted(source.getFileName()), e);
        }
    }

    public static List<String> supportedExtensions() {
        return List.of("pdf", "docx", "doc", "txt", "md");
    }

    private DocumentAttachment extractPdf(Path source) throws IOException, DocumentExtractionException {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder out = new StringBuilder();
            boolean truncated = false;
            int pages = document.getNumberOfPages();

            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = clean(stripper.getText(document));
                if (pageText.isBlank()) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append("--- Page ").append(page).append(" ---\n").append(pageText);
                if (out.length() >= MAX_CHARS) {
                    truncated = true;
                    break;
                }
            }

            String text = finish(out, truncated);
            if (text.isBlank()) {
                throw new DocumentExtractionException(
                        "Puppy couldn't find selectable text in \"%s\". Scanned or image-only PDFs are not supported yet."
                                .formatted(source.getFileName()));
            }
            return new DocumentAttachment(source, text, pages, truncated);
        } catch (InvalidPasswordException e) {
            throw new DocumentExtractionException(
                    "\"%s\" is password-protected. Please unlock it before attaching."
                            .formatted(source.getFileName()), e);
        }
    }

    private DocumentAttachment extractDocx(Path source) throws IOException, DocumentExtractionException {
        try (InputStream input = Files.newInputStream(source);
             XWPFDocument document = new XWPFDocument(input)) {
            StringBuilder out = new StringBuilder();
            boolean truncated = false;

            for (IBodyElement element : document.getBodyElements()) {
                if (out.length() >= MAX_CHARS) {
                    truncated = true;
                    break;
                }
                if (element instanceof XWPFParagraph paragraph) {
                    String text = clean(paragraph.getText());
                    if (!text.isBlank()) {
                        out.append(text).append('\n');
                    }
                } else if (element instanceof XWPFTable table) {
                    appendTable(out, table);
                }
            }

            String text = finish(out, truncated || out.length() > MAX_CHARS);
            if (text.isBlank()) {
                throw new DocumentExtractionException(
                        "Puppy couldn't find readable text in \"%s\"."
                                .formatted(source.getFileName()));
            }
            return new DocumentAttachment(source, text, 0, truncated || out.length() > MAX_CHARS);
        }
    }

    private DocumentAttachment extractDoc(Path source) throws IOException, DocumentExtractionException {
        try (InputStream input = Files.newInputStream(source);
             HWPFDocument document = new HWPFDocument(input)) {
            WordExtractor extractor = new WordExtractor(document);
            String cleaned = clean(extractor.getText());
            boolean truncated = cleaned.length() > MAX_CHARS;
            String text = finish(new StringBuilder(cleaned), truncated);
            if (text.isBlank()) {
                throw new DocumentExtractionException(
                        "Puppy couldn't find readable text in \"%s\"."
                                .formatted(source.getFileName()));
            }
            return new DocumentAttachment(source, text, 0, truncated);
        }
    }

    private DocumentAttachment extractPlainText(Path source) throws IOException, DocumentExtractionException {
        String text = clean(Files.readString(source, StandardCharsets.UTF_8));
        if (text.isBlank()) {
            throw new DocumentExtractionException(
                    "\"%s\" is empty.".formatted(source.getFileName()));
        }
        StringBuilder out = new StringBuilder(text);
        boolean truncated = out.length() > MAX_CHARS;
        return new DocumentAttachment(source, finish(out, truncated), 0, truncated);
    }

    private static void appendTable(StringBuilder out, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            List<XWPFTableCell> cells = row.getTableCells();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    out.append(" | ");
                }
                String cell = clean(cells.get(i).getText());
                out.append(cell.replace('\n', ' '));
                if (out.length() >= MAX_CHARS) {
                    return;
                }
            }
            out.append('\n');
        }
    }

    private static String finish(StringBuilder out, boolean truncated) {
        if (out.length() > MAX_CHARS) {
            truncated = true;
        }
        if (truncated) {
            int cut = safeCut(out, MAX_CHARS - TRUNCATION_NOTICE.length());
            return out.substring(0, cut).stripTrailing() + TRUNCATION_NOTICE;
        }
        return out.toString().strip();
    }

    private static int safeCut(CharSequence text, int limit) {
        int cut = Math.min(Math.max(limit, 0), text.length());
        if (cut > 0 && cut < text.length() && Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        return cut;
    }

    private static String clean(String value) {
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', ' ')
                .replace("\f", "\n")
                .strip();
    }

    private static String extensionOf(Path source) {
        Path name = source.getFileName();
        if (name == null) {
            return "";
        }
        String fileName = name.toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
