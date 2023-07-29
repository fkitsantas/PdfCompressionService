# PDF Compression Service

## Table of Contents
- [About The Project](#about-the-project)
- [Technical Stack](#technical-stack)
- [Features](#features)
- [API Description](#api-description)
- [Setup](#setup)
- [Usage](#usage)
- [Quick Start Guide](#usage)
- [License](#license)

## About The Project

This is a microservice that provides a RESTful API endpoint for compressing PDF files. 

## Technical Stack

This microservice is built using the following technologies:

- **Spring Boot**: Java Framework used to create a RESTful web service.
- **Apache PDFBox**: Library used for handling PDF files.

## Features

- Compresses PDF files by optimizing the images in the file.
- Returns the compressed PDF file as a download.

## API Description

### POST /compressPdf

Compresses a PDF file.

#### Parameters

- `file`: The PDF file to be compressed.

#### Returns

A compressed PDF file.

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

The service will be available at `http://localhost:7777`.

## Usage

To compress a PDF file, make a POST request to `http://localhost:7777/compressPdf` with the file in the request body.

## Quick Start Guide

If you are not familiar with compiling and running Java applications from source code, you can simply download and run the pre-compiled JAR file.

### Step 1: Download the JAR file

First, download the [in a zipped format pre-compiled JAR file](https://github.com/fkitsantas/PdfCompressionService/files/12207359/PdfCompressionService.jar.zip) and then unzip it into a folder.

### Step 2: Run the JAR file

Open a terminal window and navigate to the location where you downloaded the JAR file. Run the JAR file using the following command:

```bash
java -jar pdfcompression-0.0.1-SNAPSHOT.jar
```
### Step 3: Use the terminal to post a .pdf file to the microservice for testing.

```bash
curl -X POST -F 'file=@/Users/User/sample.pdf' http://localhost:7777/compressPdf --output compressed.pdf'
```

## License

Distributed under the GPL-3.0 License. See `LICENSE` for more information.
