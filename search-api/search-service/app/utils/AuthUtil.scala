package utils

import play.api.mvc.RequestHeader
import play.api.libs.json._
import com.typesafe.config.ConfigFactory
import org.apache.commons.lang3.StringUtils

object AuthUtil {
  private val config = ConfigFactory.load()
  private val AUTH_ISSUER = if (config.hasPath("auth.issuer")) config.getString("auth.issuer") else ""
  private val UNAUTHORIZED = "UNAUTHORIZED"

  def verifyUser(request: RequestHeader): String = {
    try {
      val tokenOpt = request.headers.get("x-authenticated-user-token")
      tokenOpt match {
        case Some(token) => verifyUserToken(token)
        case None => request.headers.get("x-authenticated-userid").getOrElse(UNAUTHORIZED)
      }
    } catch {
      case _: Throwable => UNAUTHORIZED
    }
  }

  def verifyUserToken(token: String): String = {
    try {
      val payload = validateToken(token)
      if (payload.nonEmpty && checkIss(payload.get("iss"))) {
        var userId = payload.getOrElse("sub", payload.getOrElse("userId", payload.getOrElse("userid", payload.getOrElse("user_id", UNAUTHORIZED)))).toString
        if (StringUtils.isNotBlank(userId) && userId != UNAUTHORIZED) {
          val pos = userId.lastIndexOf(":")
          if (pos >= 0 && pos < userId.length - 1) userId = userId.substring(pos + 1)
        }
        userId
      } else {
        UNAUTHORIZED
      }
    } catch {
      case _: Throwable => UNAUTHORIZED
    }
  }

  def validateToken(token: String): Map[String, Any] = {
    try {
      val t = if (token.startsWith("Bearer ")) token.substring("Bearer ".length) else token
      val parts = t.split("\\.")
      if (parts.length < 2) return Map.empty
      var payloadB64 = parts(1).replace('-', '+').replace('_', '/')
      val pad = payloadB64.length % 4
      if (pad == 2) payloadB64 += "=="
      else if (pad == 3) payloadB64 += "="
      else if (pad == 1) payloadB64 += "==="
      val bytes = javax.xml.bind.DatatypeConverter.parseBase64Binary(payloadB64)
      val payload = new String(bytes, "UTF-8")
      val js = Json.parse(payload)
      js.as[Map[String, JsValue]].mapValues {
        case JsString(s) => s
        case JsNumber(n) => n.toString
        case JsBoolean(b) => b
        case other => other.toString()
      }
    } catch {
      case _: Throwable => Map.empty
    }
  }

  private def checkIss(issOpt: Any): Boolean = {
    try {
      if (AUTH_ISSUER == null || AUTH_ISSUER.trim.isEmpty) true
      else if (issOpt == null) false
      else AUTH_ISSUER == issOpt.toString
    } catch {
      case _: Throwable => false
    }
  }
}

