package org.sunbird.content.upload.mgr.validator

import java.io.File
import org.apache.tika.Tika

object FileUploadValidator {

  private val tika = new Tika()

  private val blockedExtensions = Set(
    "php", "php3", "php4", "php5", "phtml",
    "jsp", "jspx",
    "asp", "aspx",
    "cgi", "pl",
    "sh", "bat", "cmd",
    "exe"
  )

  private val mimeAliases = Map(
    "image/jpg" -> Set("image/jpeg"),
    "image/jpeg" -> Set("image/jpg"),

    "application/epub" -> Set("application/epub+zip"),
    "application/epub+zip" -> Set("application/epub")
  )

  def validate(file: File, metadataMimeType: String): Unit = {

    // Extension validation
    val extension =
      Option(file.getName)
        .filter(_.contains("."))
        .map(_.substring(file.getName.lastIndexOf('.') + 1).toLowerCase.trim)
        .getOrElse("")

    if (blockedExtensions.contains(extension)) {
      throw new IllegalArgumentException(
        s"File type not allowed: $extension"
      )
    }

    // Magic-byte/content validation
    val detectedMimeType =
      Option(tika.detect(file))
        .getOrElse("")
        .toLowerCase
        .trim

    val expectedMimeType =
      Option(metadataMimeType)
        .getOrElse("")
        .toLowerCase
        .trim

    // Exact match
    if (expectedMimeType == detectedMimeType) {
      return
    }

    // Alias match
    val aliases =
      mimeAliases.getOrElse(
        expectedMimeType,
        Set.empty[String]
      )

    if (detectedMimeType == "image/svg+xml") {
      validateSvg(file)
    }

    if (!aliases.contains(detectedMimeType)) {
      throw new IllegalArgumentException(
        s"Mime mismatch. Metadata=$expectedMimeType Detected=$detectedMimeType"
      )
    }
  }

  private def validateSvg(file: File): Unit = {

    val svg = scala.xml.XML.loadFile(file)

    val dangerousTags = Set(
      "script",
      "foreignObject",
      "iframe",
      "object",
      "embed"
    ).map(_.toLowerCase)

    svg.descendant.foreach {

      case elem: scala.xml.Elem =>

        val tagName = elem.label.toLowerCase

        // Block dangerous tags
        if (dangerousTags.contains(tagName)) {
          throw new IllegalArgumentException(
            s"Unsafe SVG element detected: $tagName"
          )
        }

        // Block dangerous attributes
        elem.attributes.asAttrMap.foreach {
          case (name, value) =>

            val attrName = name.toLowerCase
            val attrValue = value.toLowerCase

            // onload, onclick, onerror...
            if (attrName.startsWith("on")) {
              throw new IllegalArgumentException(
                s"Unsafe SVG attribute detected: $name"
              )
            }

            // javascript:, vbscript:
            if (
              attrValue.contains("javascript:") ||
                attrValue.contains("vbscript:")
            ) {
              throw new IllegalArgumentException(
                s"Unsafe SVG URI detected"
              )
            }
        }

      case _ =>
    }
  }
}