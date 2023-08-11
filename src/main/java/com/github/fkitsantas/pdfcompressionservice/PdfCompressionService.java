package com.github.fkitsantas.pdfcompressionservice;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * This class provides a RESTful API endpoint as a Microservice for compressing PDF files.
 */
@RestController
public class PdfCompressionService {

    //Constants for maximum image dimensions
    private static final int MAX_WIDTH = 1000; // Maximum width of images in the PDF
    private static final int MAX_HEIGHT = 1000; // Maximum height of images in the PDF
    //Logger to log events
    private static final Logger logger = LoggerFactory.getLogger(PdfCompressionService.class);

    /**
     * This method handles POST requests to compress a PDF file.
     * It reads the file, optimizes the images in the file, and returns the optimized file.
     *
     * @param file The PDF file to be compressed.
     * @return The compressed PDF file as an InputStreamResource
     * @throws IOException If there is an error reading or writing the PDF
     */
    @PostMapping("/compressPdf")
    public ResponseEntity<InputStreamResource> compressPdf(MultipartFile file) throws IOException {
        //Log when compression starts and the file name
        logger.info("Starting PDF compression process for file: {}", file.getOriginalFilename());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            try (ByteArrayInputStream in = new ByteArrayInputStream(file.getBytes())) {
                PDDocument doc = PDDocument.load(in);
                logger.info("Loaded PDF document with {} pages", doc.getNumberOfPages());

                // Iterate through each page of the PDF
                for (PDPage page : doc.getPages()) {
                    PDResources resources = page.getResources();

                    // Iterate through each XObject in the page resources
                    for (COSName name : resources.getXObjectNames()) {
                        PDXObject xobject = resources.getXObject(name);

                        // Check if the XObject is an image
                        if (xobject instanceof PDImageXObject) {

                            // Log the initiation of the compression process for the current image object
                            logger.info("Compressing content stream for Image object: {}", name.getName());

                            // Cast the xobject to PDImageXObject for further processing
                            PDImageXObject image = (PDImageXObject) resources.getXObject(name);

                            // Retrieve the original image from the image object
                            BufferedImage original = image.getImage();

                            // Check if the image dimensions exceed the maximum allowed dimensions
                            if (image.getWidth() > MAX_WIDTH || image.getHeight() > MAX_HEIGHT) {

                                // Initialize new dimensions with the original image dimensions
                                int newWidth = image.getWidth();
                                int newHeight = image.getHeight();

                                // Determine the aspect ratio for resizing
                                if (image.getWidth() > image.getHeight()) {
                                    // Landscape orientation: adjust width to MAX_WIDTH and scale height proportionally
                                    newWidth = MAX_WIDTH;
                                    newHeight = (int) (newWidth * image.getHeight() / image.getWidth());
                                } else {
                                    // Portrait orientation: adjust height to MAX_HEIGHT and scale width proportionally
                                    newHeight = MAX_HEIGHT;
                                    newWidth = (int) (newHeight * image.getWidth() / image.getHeight());
                                }

                                // Create a new resized image with the determined dimensions
                                BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);

                                // Draw the original image onto the resized image
                                Graphics2D g = resized.createGraphics();
                                g.drawImage(original, 0, 0, newWidth, newHeight, null);
                                g.dispose();

                                // Update the original image reference to point to the resized image
                                original = resized;
                            }

                            // Convert the image to RGB format for JPEG compression
                            BufferedImage rgb = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
                            rgb.createGraphics().drawImage(original, 0, 0, null);

                            // Prepare an output stream for the compressed JPEG image
                            ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();

                            // Compress the RGB image to JPEG format
                            ImageIO.write(rgb, "jpeg", jpegOut);

                            // Create a new PDImageXObject from the compressed JPEG byte array
                            PDImageXObject optimized = PDImageXObject.createFromByteArray(doc, jpegOut.toByteArray(), name.getName());

                            // Replace the original image object in the resources with the compressed one
                            resources.put(name, optimized);
                        }
                        // Check if the XObject is a form
                        else if (xobject instanceof PDFormXObject) {
                            // Log the initiation of the compression process for the current form object
                            logger.info("Compressing content stream for form object: {}", name.getName());
                            // Cast the xobject to PDFormXObject for further processing
                            PDFormXObject form = (PDFormXObject) xobject;
                            // Compress the content stream of the form object
                            PDFormXObject compressedForm = compressContentStream(doc, form);
                            // Replace the original form object in the resources with the compressed one
                            resources.put(name, compressedForm);
                        }
                    }
                }
                // Save the optimized PDF to the byte array and close the document
                doc.save(out);
                doc.close();
                logger.info("Saved compressed PDF document");

            } catch (IOException e) {
                logger.error("Error compressing PDF", e);
                return ResponseEntity.status(500).build();
            }

            // Get the byte array of the optimized PDF
            byte[] optimizedPdf = out.toByteArray();

            // Get the length of the optimized PDF
            int length = optimizedPdf.length;
            long optimizedSize = optimizedPdf.length;

            logger.info("Completed PDF compression request");
            logger.info("Original size: {} bytes", file.getSize());
            logger.info("Optimized size: {} bytes", optimizedSize);

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

        } catch (IOException e) {
            logger.error("I/O error compressing PDF", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Compresses the content stream of a given form object.
     *
     * @param doc  The parent PDF document
     * @param form The form object to compress
     * @return The compressed form object
     * @throws IOException If there's an error during compression
     */
    private PDFormXObject compressContentStream(PDDocument doc, PDFormXObject form) throws IOException {
        // Define the content stream name constant
        COSName contentStreamName = COSName.CONTENTS;

        // Retrieve the content stream from the form object
        COSBase contentStreamBase = form.getCOSObject().getItem(contentStreamName);

        // Check if the content stream is null
        if (contentStreamBase == null) {
            logger.warn("Encountered form object with null content stream");
            return form; // If null, return the original form object without compression
        }

        // Check if the content stream is of type COSStream
        if (contentStreamBase instanceof COSStream) {
            COSStream contentStream = (COSStream) contentStreamBase;

            // Read the content stream into a string
            try (InputStream is = contentStream.createInputStream()) {
                String uncompressedText = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                // Compress the content stream text
                String compressedText = compressText(uncompressedText);

                // Create a new COSStream for the compressed content
                COSStream compressedStream = doc.getDocument().createCOSStream();

                // Write the compressed text into the new COSStream
                try (OutputStream outputStream = compressedStream.createOutputStream(COSName.FLATE_DECODE)) {
                    outputStream.write(compressedText.getBytes(StandardCharsets.UTF_8));
                }

                // Create a new form object with the compressed content stream
                PDFormXObject compressedForm = new PDFormXObject(doc);
                compressedForm.setResources(form.getResources());
                compressedForm.getCOSObject().setItem(contentStreamName, compressedStream);

                return compressedForm;
            }
        } else {
            // Log a warning if the content stream format is unsupported
            logger.warn("Encountered form object with unsupported content stream format: {}", contentStreamBase.getClass().getName());
            return form; // Return the original form object without compression
        }
    }


    /**
     * Compresses a given text using a basic run-length encoding algorithm.
     *
     * @param text The text to compress
     * @return The compressed text
     */
    private String compressText(String text) {

        // StringBuilder to build the compressed text
        StringBuilder compressed = new StringBuilder();

        // Initialize a counter to keep track of consecutive character repetitions
        int count = 1;

        // Store the first character of the text
        char prevChar = text.charAt(0);

        // Iterate through the text starting from the second character
        for (int i = 1; i < text.length(); i++) {
            char currentChar = text.charAt(i);

            // If the current character is the same as the previous one, increment the counter
            if (currentChar == prevChar) {
                count++;
            } else {
                // If the current character is different, append the count and the previous character to the compressed text
                compressed.append(count).append(prevChar);

                // Reset the counter and update the previous character
                count = 1;
                prevChar = currentChar;
            }
        }

        // Append the count and the last character to the compressed text
        compressed.append(count).append(prevChar);

        return compressed.toString();
    }
}