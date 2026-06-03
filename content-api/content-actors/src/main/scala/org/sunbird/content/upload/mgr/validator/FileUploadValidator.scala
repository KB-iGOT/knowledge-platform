package org.sunbird.content.upload.mgr.validator

import java.io.File
import org.apache.tika.Tika

object FileUploadValidator {

  private val tika = new Tika()

  private val blockedExtensions = Set(
    "php",
    "php3",
    "php4",
    "php5",
    "phtml",
    "jsp",
    "jspx",
    "asp",
    "aspx",
    "cgi",
    "pl",
    "sh",
    "bat",
    "cmd",
    "exe"
  )

  private val mimeAliases = Map(
    "image/jpg" -> Set("image/jpeg"),
    "image/jpeg" -> Set("image/jpg"),

    "application/epub" -> Set("application/epub+zip"),
    "application/epub+zip" -> Set("application/epub")
  )

  def validate(file: File, metadataMimeType: String): Unit = {

    require(file != null, "Uploaded file cannot be null")

    // Step 1: Block dangerous extensions
    val extension =
      Option(file.getName)
        .filter(_.contains("."))
        .map(_.substring(file.getName.lastIndexOf('.') + 1).toLowerCase)
        .getOrElse("")

    if (blockedExtensions.contains(extension)) {
      throw new IllegalArgumentException(
        s"File type not allowed: $extension"
      )
    }

    // Step 2: Detect actual content type using magic bytes/content
    val detectedMimeType =
      Option(tika.detect(file))
        .getOrElse("")
        .toLowerCase

    // Step 3: Validate metadata mime vs actual mime
    val expectedMimeType =
      Option(metadataMimeType)
        .getOrElse("")
        .toLowerCase

    val allowedDetectedMimeTypes =
      mimeAliases.getOrElse(
        expectedMimeType,
        Set(expectedMimeType)
      )

    if (!allowedDetectedMimeTypes.contains(detectedMimeType)) {
      throw new IllegalArgumentException(
        s"Mime mismatch. Metadata=$expectedMimeType Detected=$detectedMimeType"
      )
    }
  }
}