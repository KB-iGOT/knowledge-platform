package controllers

import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.Inject
import com.google.inject.name.Named
import handlers.LoggingAction
import managers.SearchManager
import org.apache.commons.lang3.StringUtils
import org.sunbird.search.util.SearchConstants
import play.api.mvc.ControllerComponents
import utils.{ActorNames, ApiId}
import java.util
import java.util.Base64
import org.sunbird.common.JsonUtils
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import org.sunbird.telemetry.logger.TelemetryManager

class ExtendedSearchController @Inject()(@Named(ActorNames.SEARCH_ACTOR) searchActor: ActorRef, loggingAction: LoggingAction, cc: ControllerComponents, actorSystem: ActorSystem)(implicit exec: ExecutionContext) extends SearchBaseController(cc) {

    val apiVersion = "3.0"

    val mgr: SearchManager = new SearchManager()

    def searchV5() = loggingAction.async { implicit request =>
        val headersString = request.headers.headers.map(h => h._1 + "=" + h._2).mkString(", ")
        TelemetryManager.info("searchV5 incoming headers: " + headersString)

        val internalReq = getRequest(ApiId.APPLICATION_SEARCH)
        val requestMap: java.util.Map[String, Any] = internalReq.getRequest.asInstanceOf[util.Map[String, Any]]
        requestMap.put(SearchConstants.isSecureSettingsDisabled, true)
        setHeaderContext(internalReq)

        // Parse JWT token from request headers to extract user_roles and org
        val tokenOpt = request.headers.get("x-authenticated-user-token")
          .orElse(request.headers.get("Authorization").map(h => if (h.startsWith("Bearer ")) h.substring(7) else h))

        var userRoles: java.util.List[String] = new java.util.ArrayList[String]()
        var orgId: String = ""

        tokenOpt.foreach { token =>
          val claims = getClaimsFromToken(token)
          if (claims != null) {
            val rolesObj = claims.get(SearchConstants.USER_ROLES)
            if (rolesObj != null && rolesObj.isInstanceOf[java.util.List[_]]) {
              userRoles = rolesObj.asInstanceOf[java.util.List[String]]
            }
            val orgObj = claims.get(SearchConstants.ORG)
            if (orgObj != null) {
              orgId = orgObj.toString
            }
          }
        }

        internalReq.getContext.put(SearchConstants.USER_ROLES, userRoles)
        internalReq.getContext.put(SearchConstants.ORG, orgId)
        internalReq.getContext.put(SearchConstants.API_VERSION, SearchConstants.VERSION_V5)

        // Telemetry cdata moves from the request body to the context, keeping it out of the search cache key.
        val cdata = getCdata(requestMap.remove(SearchConstants.cdata))
        if (!cdata.isEmpty) internalReq.getContext.put(SearchConstants.cdata, cdata)

        val filters = internalReq.getRequest.getOrDefault(SearchConstants.filters, new java.util.HashMap()).asInstanceOf[java.util.Map[String, Object]]
        val visibilityObject = filters.getOrDefault("visibility","")
        var visibility:util.List[String] = null
        if (visibilityObject != null) {
            if (visibilityObject.isInstanceOf[util.ArrayList[_]]) visibility = visibilityObject.asInstanceOf[util.ArrayList[String]]
            else if (visibilityObject.isInstanceOf[String]) visibility = util.Arrays.asList(visibilityObject.asInstanceOf[String])
        }
        if (visibility != null && visibility.contains("Private")) {
            getErrorResponse(ApiId.APPLICATION_SEARCH, apiVersion, SearchConstants.ERR_ACCESS_DENIED, "Cannot access private content through public search api")
        }
        else {
            internalReq.getContext.put(SearchConstants.setDefaultVisibility, "true")
            getResult(mgr.search(internalReq, searchActor), ApiId.APPLICATION_SEARCH)
        }
    }

    // Accepts cdata as a single {id, type} object or a list of them; entries without both id and type are dropped.
    private def getCdata(cdataObj: Any): java.util.List[java.util.Map[String, Object]] = {
        val entries: List[Any] = cdataObj match {
            case list: java.util.List[_] => list.asScala.toList
            case map: java.util.Map[_, _] => List(map)
            case _ => List()
        }
        entries.collect { case entry: java.util.Map[_, _] => (entry.get("id"), entry.get("type")) }
          .collect { case (id: String, cdataType: String) if StringUtils.isNotBlank(id) && StringUtils.isNotBlank(cdataType) =>
              val item: java.util.Map[String, Object] = new java.util.HashMap[String, Object]()
              item.put("id", id)
              item.put("type", cdataType)
              item
          }.asJava
    }

    private def getClaimsFromToken(token: String): java.util.Map[String, Object] = {
        try {
            val parts = token.split("\\.")
            if (parts.length >= 2) {
                val payloadBytes = Base64.getUrlDecoder.decode(parts(1))
                val payloadString = new String(payloadBytes, "UTF-8")
                JsonUtils.deserialize(payloadString, classOf[java.util.Map[String, Object]])
            } else {
                new java.util.HashMap[String, Object]()
            }
        } catch {
            case NonFatal(e) =>
                new java.util.HashMap[String, Object]()
        }
    }

    def blendedProgramSearch() = loggingAction.async { implicit request =>
        val internalReq = getRequest(ApiId.BP_SEARCH)
        val requestMap: java.util.Map[String, Any] = internalReq.getRequest.asInstanceOf[util.Map[String, Any]]
        requestMap.put(SearchConstants.isSecureSettingsDisabled, true)
        setHeaderContext(internalReq)

        val tokenOpt = request.headers.get(SearchConstants.X_AUTHENTICATED_USER_TOKEN)
          .orElse(request.headers.get(SearchConstants.AUTHROIZATION).map(h => if (h.startsWith(SearchConstants.BEARER)) h.substring(7) else h))

        var userId: String = SearchConstants.UNAUTHORIZED
        tokenOpt.foreach { token =>
            val claims = getClaimsFromToken(token)
            userId = extractUserIdFromClaims(claims)
        }

        if (StringUtils.isBlank(userId) || SearchConstants.UNAUTHORIZED.equals(userId)) {
            getErrorResponse(ApiId.APPLICATION_SEARCH, apiVersion, SearchConstants.ERR_ACCESS_DENIED, "User ID not found in token")
        } else {
            internalReq.getContext.put(SearchConstants.USER_ID, userId)
            internalReq.getContext.put(SearchConstants.BLENDED_PROGRAM_SEARCH, Boolean.box(true))
            internalReq.getContext.put(SearchConstants.setDefaultVisibility, SearchConstants.TRUE)

            getResult(mgr.search(internalReq, searchActor), ApiId.APPLICATION_SEARCH)
        }
    }

    private def extractUserIdFromClaims(claims: java.util.Map[String, Object]): String = {
        try {
            if (claims == null || claims.isEmpty) {
                return SearchConstants.UNAUTHORIZED
            }
            var sub = claims.get(SearchConstants.SUB).asInstanceOf[String]
            if (StringUtils.isNotBlank(sub)) {
                sub = sub.substring(sub.lastIndexOf(":") + 1)
                sub
            } else {
                SearchConstants.UNAUTHORIZED
            }
        } catch {
            case NonFatal(e) =>
                TelemetryManager.error("Exception extracting userId from claims", e)
                SearchConstants.UNAUTHORIZED
        }
    }
}
