package org.sunbird.content.upload.mgr.validator

import java.net.URI

import org.apache.commons.lang3.StringUtils
import org.sunbird.common.Platform

object ArtifactUrlValidator {

  private lazy val allowedDomains: Set[String] =
    Platform.config
      .getStringList("artifact.url.allowed.domains")
      .toArray
      .map(_.toString.toLowerCase.trim)
      .toSet

  def isValid(url: String): Boolean = {

    if (StringUtils.isBlank(url))
      return false

    try {
      val uri = new URI(url.trim)

      val scheme =
        Option(uri.getScheme)
          .map(_.toLowerCase)
          .getOrElse("")

      val host =
        Option(uri.getHost)
          .map(_.toLowerCase)
          .getOrElse("")

      if (scheme != "https")
        return false

      isAllowedHost(host)

    } catch {
      case _: Exception => false
    }
  }

  private def isAllowedHost(host: String): Boolean = {
    allowedDomains.exists { domain =>
      host == domain || host.endsWith("." + domain)
    }
  }
}
