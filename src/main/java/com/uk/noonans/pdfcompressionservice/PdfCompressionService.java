package com.uk.noonans.pdfcompressionservice;

import java.awt.Graphics2D;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.*;
import org.apache.pdfbox.pdmodel.PDDocument;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletRequest;

/**
 * This class provides a RESTful API endpoint as a micro-service for compressing PDF files.
 */
@RestController
public class PdfCompressionService {

    private static final int MAX_WIDTH = 1000; // Maximum width of images in the PDF
    private static final int MAX_HEIGHT = 1000; // Maximum height of images in the PDF
    private static final Logger logger = LoggerFactory.getLogger(PdfCompressionService.class); // Logger for the class

    /**
     * This method handles POST requests to compress a PDF file.
     * It reads the file, optimizes the images in the file, and returns the optimized file.
     *
     * @param file    The PDF file to be compressed.
     * @return A ResponseEntity containing the compressed PDF file.
     * @throws IOException If an I/O error occurs.
     */
    @PostMapping("/compressPdf")
    public ResponseEntity<InputStreamResource> compressPdf(MultipartFile file) throws IOException {

        try {
            // Log the incoming request
            logger.info("Received request to compress PDF file '{}'", file.getOriginalFilename());

            // Input validation
            if(file == null || file.isEmpty()) {
                logger.error("Empty or invalid file received in request");
                return ResponseEntity.badRequest().build();
            }else {
                logger.debug("Compressing PDF file '{}'", file.getOriginalFilename());
                logger.trace("File size {} bytes", file.getSize());
            }

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

                    // Check if the width or height of the image exceeds the maximum allowed dimensions
                    if (image.getWidth() > MAX_WIDTH || image.getHeight() > MAX_HEIGHT) {

                        // Initialize newWidth and newHeight with the original dimensions
                        int newWidth = image.getWidth();
                        int newHeight = image.getHeight();

                        // Check if the image is in a landscape or portrait format
                        if (image.getWidth() > image.getHeight()) { // landscape
                            newWidth = MAX_WIDTH;
                            newHeight = (int) (newWidth * image.getHeight() / image.getWidth());
                        } else { // portrait
                            newHeight = MAX_HEIGHT;
                            newWidth = (int) (newHeight * image.getWidth() / image.getHeight());
                        }

                        // Create new BufferedImage to hold resized image
                        BufferedImage resized = new BufferedImage(newWidth, newHeight, original.getType());

                        // Draw original image into resized image
                        Graphics2D g = resized.createGraphics();
                        g.drawImage(original, 0, 0, newWidth, newHeight, null);

                        // Set original to resized version
                        original = resized;

                    }

                    // Create new BufferedImage for RGB conversion
                    BufferedImage rgb = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
                    // Draw resized original image into RGB version
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

            logger.info("Completed PDF compression request");

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
        } catch (Exception e) {
            logger.error("Error compressing PDF", e);
            return ResponseEntity.status(500).build();
        }

    }

}