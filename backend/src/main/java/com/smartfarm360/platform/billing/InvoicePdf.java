package com.smartfarm360.platform.billing;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The PDF of a tax invoice or credit note (billing design, P6), in English: the legal copy. It is drawn from the
 * stored document each time, so it never differs from what was issued. It shows what GST rule 46 asks for: both
 * parties' names, addresses and GSTINs, the number and date, the place of supply, the SAC, the taxable value and each
 * tax with its rate.
 */
final class InvoicePdf {
    private InvoicePdf() {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font TEXT = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f);

    static byte[] invoice(InvoiceService.Invoice inv) {
        return render("TAX INVOICE", inv, null);
    }

    static byte[] creditNote(InvoiceService.Invoice inv, InvoiceService.CreditNote note) {
        return render("CREDIT NOTE", inv, note);
    }

    private static byte[] render(String title, InvoiceService.Invoice inv, InvoiceService.CreditNote note) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(doc, out);
        doc.addTitle(title + " " + (note == null ? inv.number() : note.number()));
        doc.addCreator("SmartFarm360");
        doc.open();
        doc.add(new Paragraph(title, TITLE));
        doc.add(new Paragraph(" ", SMALL));

        PdfPTable head = new PdfPTable(2);
        head.setWidthPercentage(100);
        head.addCell(block("Supplier", inv.sellerName(), inv.sellerAddress(), "GSTIN: " + inv.sellerGstin(), state(inv.sellerStateCode())));
        if (note == null) {
            head.addCell(block("Invoice", "Number: " + inv.number(), "Date: " + DATE.format(inv.invoiceDate()),
                    "Place of supply: " + state(inv.buyerStateCode()), "Reverse charge: No"));
        } else {
            head.addCell(block("Credit note", "Number: " + note.number(), "Date: " + DATE.format(note.noteDate()),
                    "Against invoice " + inv.number() + " of " + DATE.format(inv.invoiceDate()), "Reason: " + note.reason()));
        }
        head.addCell(block("Recipient", inv.buyerName(), inv.buyerAddress(), inv.buyerGstin() == null ? "GSTIN: unregistered" : "GSTIN: " + inv.buyerGstin(),
                "Farm business: " + inv.businessName()));
        head.addCell(block("", "", "", "", ""));
        doc.add(head);
        doc.add(new Paragraph(" ", SMALL));

        PdfPTable lines = new PdfPTable(new float[]{5f, 1.3f, 2.6f, 1.8f});
        lines.setWidthPercentage(100);
        for (String h : new String[]{"Description", "SAC", "Period", "Taxable value (INR)"}) lines.addCell(cell(h, BOLD, Element.ALIGN_LEFT));
        BigDecimal taxable = note == null ? inv.taxableAmount() : note.taxableAmount();
        lines.addCell(cell(inv.description(), TEXT, Element.ALIGN_LEFT));
        lines.addCell(cell(inv.sac(), TEXT, Element.ALIGN_LEFT));
        lines.addCell(cell(DATE.format(inv.periodStart()) + " to " + DATE.format(inv.periodEnd()), TEXT, Element.ALIGN_LEFT));
        lines.addCell(cell(money(taxable), TEXT, Element.ALIGN_RIGHT));
        BigDecimal cgst = note == null ? inv.cgst() : note.cgst();
        BigDecimal sgst = note == null ? inv.sgst() : note.sgst();
        BigDecimal igst = note == null ? inv.igst() : note.igst();
        if (inv.igstRate().signum() == 0) {
            total(lines, "CGST at " + rate(inv.cgstRate()), cgst, TEXT);
            total(lines, "SGST at " + rate(inv.sgstRate()), sgst, TEXT);
        } else {
            total(lines, "IGST at " + rate(inv.igstRate()), igst, TEXT);
        }
        total(lines, note == null ? "Total (INR)" : "Total credited (INR)", note == null ? inv.total() : note.total(), BOLD);
        doc.add(lines);
        doc.add(new Paragraph(" ", SMALL));
        doc.add(new Paragraph("Software as a service: the SmartFarm360 " + inv.planCode() + " plan, billed " + inv.period().toLowerCase(Locale.ENGLISH) + ".", SMALL));
        doc.add(new Paragraph("Tax is not payable on reverse charge. This is a computer-generated document and needs no signature.", SMALL));
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell block(String heading, String... lines) {
        PdfPCell c = new PdfPCell();
        c.setBorder(PdfPCell.NO_BORDER);
        c.setPaddingBottom(6);
        if (!heading.isEmpty()) c.addElement(new Paragraph(heading, BOLD));
        for (String l : lines) if (l != null && !l.isEmpty()) c.addElement(new Paragraph(l, TEXT));
        return c;
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        return c;
    }

    private static void total(PdfPTable t, String label, BigDecimal amount, Font font) {
        PdfPCell l = cell(label, font, Element.ALIGN_RIGHT);
        l.setColspan(3);
        t.addCell(l);
        t.addCell(cell(money(amount), font, Element.ALIGN_RIGHT));
    }

    private static String state(String code) { return Gst.STATES.getOrDefault(code, "State") + " (" + code + ")"; }

    private static String rate(BigDecimal r) { return r.stripTrailingZeros().toPlainString() + "%"; }

    private static String money(BigDecimal v) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(v);
    }
}
