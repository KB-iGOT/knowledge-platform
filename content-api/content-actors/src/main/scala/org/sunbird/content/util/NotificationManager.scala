package org.sunbird.content.util

import com.mashape.unirest.http.Unirest
import org.sunbird.common.Platform
import org.sunbird.util.{HTTPResponse, HttpUtil}
import scala.collection.JavaConverters._

object NotificationManager {

  def sendNotification(subCategory: String, subType: String, userIds: List[String], title: String, data: Map[String, Any]): Unit = {

    val userIdsJson = userIds.map(id => s""""$id"""").mkString("[", ",", "]")
    val dataJson = toJsonString(data)

    val body =
      s"""
    {
      "request": {
        "subCategory": "$subCategory",
        "subType": "$subType",
        "userIds": $userIdsJson,
        "title": "$title",
        "data": $dataJson
      }
    }
  """

    val url: String = Platform.getString("notification.api.url", "http://cb-notification-wrapper-service:8081/notifications/create")
    val response = Unirest.post(url).headers(Map[String, String]("Content-Type"->"application/json").asJava).body(body).asString()
    HTTPResponse(response.getStatus, response.getBody)
  }

  private def toJsonString(map: Map[String, Any]): String = {
    map.map {
      case (k, v: String) => s""""$k":"$v""""
      case (k, v: Int) => s""""$k":$v"""
      case (k, v: Boolean) => s""""$k":$v"""
      case (k, v: Double) => s""""$k":$v"""
      case (k, v: List[_]) => s""""$k":[${v.map(x => s""""$x"""").mkString(",")}]"""
      case (k, v) => s""""$k":"${v.toString}""""
    }.mkString("{", ",", "}")
  }
}