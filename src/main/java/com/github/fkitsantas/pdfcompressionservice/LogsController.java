package com.github.fkitsantas.pdfcompressionservice;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.text.StringEscapeUtils; // Apache Commons Text library for escaping special HTML characters

/**
 * LogsController provides endpoints for fetching both output and error log files of the PDF compression service,
 * and rendering them in the view.
 */
@Controller
public class LogsController {

    /**
     * This method is mapped to the "/logs" endpoint and is responsible for
     * fetching the content of both output and error logs and passing them
     * to the view.
     *
     * @param model An instance of the Model interface, to add attributes to the model.
     * @return The name of the view to render, in this case "logs".
     * @throws IOException If an I/O error occurs reading from the file or a malformed or unmappable byte sequence is read.
     */
    @GetMapping("/logs")
    public String getLogsPage(Model model) throws IOException {
        // Adding the output log to the model
        model.addAttribute("outputLog", tailLogFile("PdfCompressionService-output.log"));

        // Adding the error log to the model
        model.addAttribute("errorLog", tailLogFile("PdfCompressionService-error.log"));

        // Returning view to render
        return "logs";
    }

    /**
     * This method reads the content of a given log file, escapes any special HTML
     * characters and returns the content as a String.
     *
     * @param filename The name of the log file to be read.
     * @return The content of the log file as a String.
     * @throws IOException If an I/O error occurs reading from the file or a malformed or unmappable byte sequence is read.
     */
    private String tailLogFile(String filename) throws IOException {
        Path path = Paths.get(filename); // Converting filename to a Path object
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8); // Reading all lines from the file
        StringBuilder sb = new StringBuilder();

        // Iterating through the lines and escaping special HTML characters
        for (String line : lines) {
            sb.append(StringEscapeUtils.escapeHtml4(line));
            sb.append("\n");
        }

        return sb.toString();
    }
}
