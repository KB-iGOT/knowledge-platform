package org.sunbird.content.upload.mgr.validator

import java.io.File
import org.apache.tika.Tika
import org.sunbird.mimetype.factory.MimeTypeManagerFactory

object FileUploadValidator {

  private val tika = new Tika()

  private val blockedExtensions = Set(
    "php",
    "php3",
    "php5",
    "jsp",
    "jspx",
    "asp",
    "aspx",
    "exe",
    "bat",
    "sh",
    "cgi"
  )

  def validate(file: File, metadataMimeType: String): Unit = {

    val extension =
      file.getName.split("\\.").last.toLowerCase

    if (blockedExtensions.contains(extension))
      throw new IllegalArgumentException(
        s"File type not allowed: $extension"
      )

    val detectedMimeType =
      tika.detect(file)

    MimeTypeManagerFactory.validateMimeType(
      metadataMimeType,
      detectedMimeType
    )
  }
}
