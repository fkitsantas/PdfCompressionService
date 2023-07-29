package com.uk.noonans.pdfcompressionservice;

import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.*;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.*;
import org.apache.pdfbox.pdmodel.PDDocument;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * This class provides a RESTful API endpoint as a micro-service for compressing PDF files.
 */
@RestController
public class PdfCompressionService {

    /**
     * This method handles POST requests to compress a PDF file.
     * It reads the file, optimizes the images in the file, and returns the optimized file.
     *
     * @param file The PDF file to be compressed.
     * @return A ResponseEntity containing the compressed PDF file.
     * @throws IOException If an I/O error occurs.
     */
    @PostMapping("/compressPdf")
    public ResponseEntity<InputStreamResource> compressPdf(MultipartFile file) throws IOException {

        // Load the PDF document
        PDDocument doc = PDDocument.load(file.getInputStream());

        // Prepare to write the optimized PDF to a byte array
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Iterate over all pages in the document
        for (PDPage page : doc.getPages()) {

            // Get the resources of the page
            PDResources resources = page.getResources();

            // Iterate over all XObjects in the resources
            for (COSName name : resources.getXObjectNames()) {

                // Get the XObject as an image
                PDImageXObject image = (PDImageXObject) resources.getXObject(name);

                // Get the image and convert it to RGB
                BufferedImage original = image.getImage();
                BufferedImage rgb = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.createGraphics().drawImage(original, 0, 0, null);

                // Prepare to write the optimized image to a byte array
                ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();

                // Write the image as JPEG to the byte array
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                writer.setOutput(ImageIO.createImageOutputStream(jpegOut));
                writer.write(null, new IIOImage(rgb, null, null), null);

                // Create an optimized image XObject from the byte array
                PDImageXObject optimized = PDImageXObject.createFromByteArray(doc, jpegOut.toByteArray(), name.getName());

                // Replace the original image with the optimized image
                resources.put(name, optimized);
            }
        }

        // Save the optimized PDF to the byte array and close the document
        doc.save(out);
        doc.close();

        // Get the byte array of the optimized PDF
        byte[] optimizedPdf = out.toByteArray();

        // Get the length of the optimized PDF
        int length = optimizedPdf.length;

        // Create an InputStreamResource from the byte array
        InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(optimizedPdf));

        // Prepare the headers for the response
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("optimized.pdf", "attachment; filename=optimized.pdf");

        // Return the optimized PDF in the response
        return ResponseEntity
                .ok()
                .headers(headers)
                .contentLength(length)
                .body(resource);
    }
}