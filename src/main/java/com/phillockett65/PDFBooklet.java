/*  PDFBooklet - a simple, crude program to generate a booklet from of a PDF.
 *
 *  Copyright 2020 Philip Lockett.
 *
 *  This file is part of PDFBooklet.
 *
 *  PDFBooklet is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  PDFBooklet is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with CardGen.  If not, see <https://www.gnu.org/licenses/>.
 */

 /*
 * As a standalone file, PDFBooklet is a simple, crude program to generate a
 * booklet from of a source PDF document. It requires 2 parameters, the source
 * PDF and the name of the new PDF. However, it can be used as a java class, in
 * which case PDFBooklet.main() should be superseded.
 *
 * Example usage:
 *  java -jar path-to-PDFBooklet.jar path-to-source.pdf path-to-new.pdf
 *
 * Dependencies:
 *  PDFbox (pdfbox-app-2.0.19.jar)
 *  https://pdfbox.apache.org/download.cgi
 *
 * This code supports multi-sheet sections. For more information on bookbinding
 * terms and techniques refer to:
 *  https://en.wikipedia.org/wiki/Bookbinding#Terms_and_techniques
 *  https://www.formaxprinting.com/blog/2016/11/
 *      booklet-layout-how-to-arrange-the-pages-of-a-saddle-stitched-booklet/
 *  https://www.studentbookbinding.co.uk/blog/
 *      how-to-set-up-pagination-section-sewn-bindings
 *
 * The implementation is crude in that the source pages are captured as images
 * which are then rotated, scaled and arranged on the pages. As a result, the
 * generated document is significantly larger and grainier.
 *
 * The document is processed in groups of 4 pages for each sheet of paper, where
 * each page is captured as a BufferedImage. The 4th page is rotated anti-
 * clockwise and scaled to fit on the bottom half of one side of the sheet. The
 * 1st page is rotated anti-clockwise and scaled to fit on the top half of the
 * same side of the sheet. On the reverse side, the 2nd page is rotated
 * clockwise and scaled to fit on the top half and the 3rd page is rotated
 * clockwise and scaled to fit on the bottom half. This process is repeated for
 * all groups of 4 pages in the source document.
 */
package com.phillockett65;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.swing.SwingWorker;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 *
 * @author Phil
 */
public class PDFBooklet {

    private int DPI = 300;         // Dots Per Inch
    private PDRectangle PS = PDRectangle.LETTER;
    private ImageType IT = ImageType.GRAY;
    private int sheetCount = 1;
    private int firstPage = 0;
    private int lastPage = 0;
    private boolean rotate = true;      // Required?

    private final String sourcePDF;     // The source PDF filepath.
    private final String outputPDF;     // The generated PDF filepath.
    private int MAX = 0;

    private PDDocument inputDoc;        // The source PDF document.
    private PDDocument outputDoc;       // The generated PDF document.
    private PDPage page;                // Current page of "outputDoc".
    private PDPageContentStream stream; // Current stream of "outputDoc".
    private float width;                // "page" width in Points Per Inch.
    private float height;               // "page" height in Points Per Inch.
    private float hHeight;              // Half height.

    // Calculate the Aspect Ratio of half the page (view port).
    private float VPAR;                 // View Port Aspect Ratio.


    /**
     * Constructor.
     *
     * @param inPDF file path for source PDF.
     * @param outPDF file path for generated PDF.
     */
    public PDFBooklet(String inPDF, String outPDF) {
        sourcePDF = inPDF;
        outputPDF = outPDF;

        try {
            inputDoc = PDDocument.load(new File(sourcePDF));
            MAX = inputDoc.getNumberOfPages();
            lastPage = MAX;

            if (inputDoc != null) {
                inputDoc.close();
            }

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * System entry point for stand alone, command line version.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            PDFBooklet booklet = new PDFBooklet(args[0], args[1]);
            booklet.setDotsPerInch(300);
            booklet.setPageSize(PDRectangle.LETTER);
            booklet.setImageType(ImageType.GRAY);

            booklet.genBooklet();
        }
    }

    /*
     * PDFBooklet attribute setters.
     */
    public void setDotsPerInch(int val) {
        DPI = val;
    }

    public void setPageSize(PDRectangle size) {
        PS = size;
    }

    public void setImageType(ImageType type) {
        IT = type;
    }

    public void setSheetCount(int count) {
        sheetCount = count;
    }

    public void setFirstPage(int page) {
        if (page < 0) {
            firstPage = 0;

            return;
        }

        if (page > MAX)
            page = MAX;

        if (page > lastPage)
            lastPage = page;

        firstPage = page;
    }

    public void setLastPage(int page) {
        if (page > MAX) {
            lastPage = MAX;

            return;
        }

        if (page < 0)
            page = 0;

        if (page < firstPage)
            firstPage = page;

        lastPage = page;
    }

    public int getFirstPage() {
        return firstPage;
    }

    public int getLastPage() {
        return lastPage;
    }

    public void setRotate(boolean flip) {
        rotate = flip;
    }

    /**
     * Based on the SwingWorker example by "MadProgrammer" here:
     * https://stackoverflow.com/questions/18835835/jprogressbar-not-updating
     */
    public class ProgressWorker extends SwingWorker<Object, Object> {

        @Override
        protected Object doInBackground() throws Exception {

            try {
                inputDoc = PDDocument.load(new File(sourcePDF));

                try {
                    outputDoc = new PDDocument();
                    final int MAX = lastPage;
                    for (int first = firstPage; first < MAX; first += 4 * sheetCount) {
                        int last = first + 4 * sheetCount;
                        if (last > MAX) {
                            last = MAX;
                        }

                        BufferedImage[] imageArray = pdfToImageArray(first, last);
                        addImagesToPdf(imageArray);
                        setProgress(100 * last / MAX);
                    }
                    outputDoc.save(outputPDF);
                    if (outputDoc != null) {
                        outputDoc.close();
                    }
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }

                if (inputDoc != null) {
                    inputDoc.close();
                }

            } catch (IOException e) {
                System.out.println(e.getMessage());
            }

            return null;
        }
    }

    /**
     * Generate a booklet style PDF using a crude images of pages technique.
     */
    public void genBooklet() {
        try {
            inputDoc = PDDocument.load(new File(sourcePDF));

            try {
                outputDoc = new PDDocument();
                final int MAX = lastPage;
                for (int first = firstPage; first < MAX; first += 4 * sheetCount) {
                    int last = first + 4 * sheetCount;
                    if (last > MAX) {
                        last = MAX;
                    }

                    BufferedImage[] imageArray = pdfToImageArray(first, last);
                    addImagesToPdf(imageArray);

                    System.out.printf("Pages %d to %d\n", first + 1, last);
                }
                outputDoc.save(outputPDF);
                if (outputDoc != null) {
                    outputDoc.close();
                }
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }

            if (inputDoc != null) {
                inputDoc.close();
            }

            System.out.println("File created in: " + outputPDF);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Create an array of images of pages from a PDF document.
     *
     * @param first page to grab from inputDoc (pages start from 0).
     * @param last stop grabbing pages BEFORE reaching the last page.
     * @return a BufferedImage array containing the page images.
     */
    private BufferedImage[] pdfToImageArray(int first, int last) {
        ArrayList<BufferedImage> images = new ArrayList<>();

        PDFRenderer renderer = new PDFRenderer(inputDoc);
        for (int target = first; target < last; ++target) {
            try {
                BufferedImage bim = renderer.renderImageWithDPI(target, DPI, IT);
                images.add(bim);
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }

        // Turn ArrayList into an array.
        BufferedImage[] imageArray = new BufferedImage[images.size()];
        imageArray = images.toArray(imageArray);

        return imageArray;
    }

    /**
     * Add images to a PDF document.
     *
     * @param images array to be added to document in booklet arrangement.
     */
    private void addImagesToPdf(BufferedImage[] images) {

        final int LAST = 4 * sheetCount;
        int first = 0;
        int last = LAST - 1;
        for (int sheet = 0; sheet < sheetCount; ++sheet) {
            addImagesToPage(images, first++, last--, false);
            addImagesToPage(images, first++, last--, rotate);
        }
    }

    /**
     * Add two images to a page of a PDF document.
     *
     * @param images array to be added to document in booklet arrangement.
     * @param top index for the top image.
     * @param bottom index for the bottom image.
     * @param flip flag to indicate if the images should be flipped clockwise.
     */
    private void addImagesToPage(BufferedImage[] images, int top, int bottom,
            boolean flip) {

        try {
            final int count = images.length;
            BufferedImage image;

            // Draw images to current page.
            addNewPage();
            startNewStream();
            if (count > top) {
                image = flip(images[top], flip);
                addImageToPdf(image, true);
            }
            if (count > bottom) {
                image = flip(images[bottom], flip);
                addImageToPdf(image, false);
            }
            endStream();

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Add a new page to "outputDoc".
     */
    private void addNewPage() {
        page = new PDPage(PS);
        outputDoc.addPage(page);

        final PDRectangle rectangle = page.getMediaBox();
        width = rectangle.getWidth();
        height = rectangle.getHeight();
        hHeight = (height / 2);

        // Calculate the Aspect Ratio of half the page (view port).
        VPAR = width / hHeight; // View Port Aspect Ratio.
    }

    /**
     * Start a new stream on the current page of "outputDoc".
     */
    private void startNewStream() {
        try {
            stream = new PDPageContentStream(outputDoc, page);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Close the current stream of "outputDoc".
     */
    private void endStream() {
        try {
            stream.close();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Add a buffered image to the top or bottom of a page in a PDF document.
     * The image is scaled to fit and centered.
     *
     * @param image to add to document.
     * @param top flag to indicate top or bottom of the page
     * @throws IOException
     */
    private void addImageToPdf(BufferedImage image, boolean top)
            throws IOException {

        final float base = top ? hHeight : 0f;

        // Calculate the Aspect Ratio of "image".
        final float w = image.getWidth();
        final float h = image.getHeight();
        final float IAR = w / h;    // "image" Aspect Ratio.

        // Calculate "scale" based on the aspect ratio of "image" and centre it.
        float scale;
        float dx = 0f;
        float dy = 0f;
        if (IAR < VPAR) {
            scale = hHeight / h;
            dx = (width - (w * scale)) / 2;
        } else {
            scale = width / w;
            dy = (hHeight - (h * scale)) / 2;
        }

        // Create the PDImage and draw it on the page.
        PDImageXObject img = LosslessFactory.createFromImage(outputDoc, image);
        stream.drawImage(img, dx, base + dy, scale * w, scale * h);
    }

    /**
     * Rotate an image 90 degrees clockwise or anti-clockwise.
     *
     * @param image to be rotated.
     * @param clockwise direction flag
     * @return the rotated image.
     */
    private static BufferedImage flip(BufferedImage image, boolean clockwise) {
        final int w = image.getWidth();
        final int h = image.getHeight();

        // Create transform.
        final AffineTransform at = new AffineTransform();
        if (clockwise) {
            at.quadrantRotate(1);
            at.translate(0, -h);
        } else {
            at.quadrantRotate(3);
            at.translate(-w, 0);
        }

        // Draw image onto rotated.
        final BufferedImage rotated = new BufferedImage(h, w, image.getType());
        Graphics2D g2d = (Graphics2D) rotated.getGraphics();
        g2d.drawImage(image, at, null);

        return rotated;
    }

}
