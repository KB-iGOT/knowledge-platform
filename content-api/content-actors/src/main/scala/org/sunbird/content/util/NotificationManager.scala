package org.sunbird.content.util

import com.mashape.unirest.http.Unirest
import org.slf4j.{Logger, LoggerFactory}
import org.sunbird.common.{JsonUtils, Platform}
import org.sunbird.util.{HTTPResponse, HttpUtil}

object NotificationManager {

  private val logger: Logger = LoggerFactory.getLogger("NotificationManager")

  def sendNotification(subCategory: String, subType: String, userIds: List[String], title: String, data: Map[String, Any]): Unit = {

    logger.info("Notification construction started")

    val placeholders = Map("title" -> title)
    val message = Map("placeholders" -> placeholders, "data" -> data)

    val bodyMap = Map(
      "subCategory" -> subCategory,
      "subType" -> subType,
      "userIds" -> userIds,
      "message" -> message
    )

    val body = JsonUtils.serialize(bodyMap)

    val url = Platform.getString("notification.api.url", "http://cb-notification-wrapper-service:8081/notifications/create")
    logger.info("Started sending notification with body {}", body)
    val response = Unirest.post(url)
      .header("Content-Type", "application/json")
      .body(body)
      .asString()

    logger.info("Successfully sent notification {}", response)

    HTTPResponse(response.getStatus, response.getBody)
  }

}