# PDF Compression Service

## Table of Contents
- [About The Project](#about-the-project)
- [Technical Stack](#technical-stack)
- [Features](#features)
- [API Description](#api-description)
   - [Compress PDF](#post-compresspdf)
   - [View Logs](#get-logs)
- [Setup](#setup)
- [Usage](#usage)
- [Quick Start Guide](#usage)
- [License](#license)

## About The Project

This is a microservice that provides RESTful API endpoints for compressing PDF files and viewing logs.

## Technical Stack

This microservice is built using the following technologies:

- **Spring Boot**: Java Framework used to create RESTful web services.
- **Apache PDFBox**: Library used for handling and manipulating PDF files.
- **SLF4J with Logback**: Logging framework to capture and display logs.

## Features

- Compresses PDF files by optimizing the images and form objects in the file.
- Returns the compressed PDF file as a download.
- Detailed logging to monitor the compression process and diagnose any issues.
- Provides an endpoint to view the logs of the service.

## API Description

### POST /compressPdf

Compresses a PDF file by optimizing its images and form objects.

#### Parameters

- `file`: The PDF file to be compressed.

#### Returns

A compressed PDF file.

### GET /logs

Provides an HTML page with the logs of the service.

#### Returns

An HTML page containing the output and error logs.

## Setup

### Prerequisites

- Java 8 or later
- Maven

### Project Setup

1. Clone the repository:

    ```bash
    git clone https://github.com/fkitsantas/PdfCompressionService.git
    ```

2. Navigate to the project directory:

    ```bash
    cd PdfCompressionService
    ```

3. Build the project:

    ```bash
    mvn clean install
    ```

4. Run the application:

    ```bash
    mvn spring-boot:run
    ```

The service will be available at port 7777. Example: `http://localhost:7777`.

## Usage

To compress a PDF file, make a POST request to `http://localhost:7777/compressPdf` with the file in the request body.

To view the logs, visit: `http://localhost:7777/logs`

## Quick Start Guide

If you are not familiar with compiling and running Java applications from source code, you can simply download and run the pre-compiled JAR file.

### Step 1: Download the JAR file

First, download the [pre-compiled JAR file](https://github.com/fkitsantas/PdfCompressionService/releases/download/v.0.0.7/PdfCompressionService.jar.zip), which is provided in a zipped format, and then unzip it into a folder.

### Step 2: Run the JAR file

Open a terminal window and navigate to the location where you downloaded the JAR file. Run the JAR file using the following command:

```bash
java -jar PdfCompressionService.jar
```
### Step 3: Use the terminal to post a .pdf file to the microservice for testing.

```bash
curl -X POST -F 'file=@/path/to/your/sample.pdf' http://localhost:7777/compressPdf --output compressed.pdf
```
Replace `/path/to/your/sample.pdf` with the actual path to your PDF file.

## License

Distributed under the GPL-3.0 License. See `LICENSE` for more information.
