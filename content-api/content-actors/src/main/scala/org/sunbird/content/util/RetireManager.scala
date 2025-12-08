package org.sunbird.content.util

import com.datastax.driver.core.{LocalDate, Session}
import com.datastax.driver.core.querybuilder.{Clause, QueryBuilder}
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture, MoreExecutors}

import java.util
import java.util.{Date, UUID}
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.lang.StringUtils
import org.slf4j.{Logger, LoggerFactory}
import org.sunbird.cache.impl.RedisCache
import org.sunbird.cassandra.CassandraConnector
import org.sunbird.common.{DateUtils, JsonUtils, Platform}
import org.sunbird.common.dto.{Request, Response, ResponseHandler}
import org.sunbird.common.exception.{ClientException, ErrorCodes, ResourceNotFoundException, ResponseCode, ServerException}
import org.sunbird.graph.dac.model.Node
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.external.ExternalPropsManager
import org.sunbird.graph.external.store.ExternalStore
import org.sunbird.graph.nodes.DataNode
import org.sunbird.graph.utils.ScalaJsonUtils
import org.sunbird.kafka.client.KafkaClient
import org.sunbird.managers.HierarchyManager
import org.sunbird.parseq.Task
import org.sunbird.telemetry.logger.TelemetryManager
import org.sunbird.util.RequestUtil
import org.sunbird.utils.HierarchyConstants

import java.time.Clock.system
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.collection.JavaConversions._
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}

object RetireManager {
    val finalStatus: util.List[String] = util.Arrays.asList("Flagged", "Live", "Unlisted")
    private val kfClient = new KafkaClient
  private val logger: Logger = LoggerFactory.getLogger("RetireManager")

  private val retirementRequestStore =
    new ExternalStore(
      "sunbird_courses",
      "content_retirement_requests",
      java.util.Arrays.asList("content_id")
    )

    def retire(request: Request)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Response] = {
        validateRequest(request)
        getNodeToRetire(request).flatMap(node => {
            val updateMetadataMap = Map(ContentConstants.STATUS -> "Retired", HierarchyConstants.LAST_UPDATED_ON -> DateUtils.formatCurrentDate, HierarchyConstants.LAST_STATUS_CHANGED_ON -> DateUtils.formatCurrentDate)
            val futureList = Task.parallel[Response](
                handleCollectionToRetire(node, request, updateMetadataMap),
                updateNodesToRetire(request, mapAsJavaMap[String,AnyRef](updateMetadataMap)))
            futureList.map(f => {
                val response = ResponseHandler.OK()
                response.put(ContentConstants.IDENTIFIER, request.get(ContentConstants.IDENTIFIER))
                response.put("node_id", request.get(ContentConstants.IDENTIFIER))
            })
        })
    }

    def scheduleRetirement(request: Request)
                (implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Response] = {
      System.out.println("Inside scheduleRetirement method of RetireManager::")
      validateRequestForContentRetirement(request)
      val outerMap = request.getRequest
      val reqMap = Option(outerMap.get("request"))
        .map(_.asInstanceOf[java.util.Map[String, AnyRef]])
        .getOrElse(throw new ClientException(
          ContentConstants.ERR_INVALID_REQUEST,
          "Request body is missing."
        ))
      val contentId = Option(reqMap.get(ContentConstants.CONTENT_ID))
        .map(_.toString.trim)
        .filter(StringUtils.isNotBlank)
        .getOrElse(throw new ClientException(
          ContentConstants.ERR_INVALID_CONTENT_ID,
          ContentConstants.ERR_CONTENT_ID_MISSING
        ))
      for {
        _ <- validateNoParentCollection(request)
        _ <- validateNoCbPlanForContent(contentId)
        _ <- insertRetirementRequest(contentId, reqMap)
        resp <- markContentPendingRetirement(request)
      } yield resp
    }

  def markContentPendingRetirement(request: Request)
                                  (implicit ec: ExecutionContext,
                                   oec: OntologyEngineContext): Future[Response] = {
    val outerMap = request.getRequest
    val reqMap = Option(outerMap.get("request"))
      .map(_.asInstanceOf[java.util.Map[String, AnyRef]])
      .getOrElse(throw new ClientException(
        ContentConstants.ERR_INVALID_REQUEST,
        "Request body is missing."
      ))
    val id = Option(reqMap.get(ContentConstants.CONTENT_ID))
      .map(_.toString.trim)
      .filter(StringUtils.isNotBlank)
      .getOrElse(throw new ClientException(
        ContentConstants.ERR_INVALID_CONTENT_ID,
        ContentConstants.ERR_CONTENT_ID_MISSING
      ))

    val readReq = new Request()
    readReq.setContext(new util.HashMap[String, AnyRef]() {{
      put("graph_id", "domain")
      put("version", "1.0")
      put("objectType", "Content")
      put("schemaName", "content")
    }})
    readReq.put("identifier", id)
    readReq.setObjectType("Content")
    readReq.put(ContentConstants.MODE, "read")
    DataNode.read(readReq).map { node =>
      if (node == null)
        throw new ClientException(
          ContentConstants.ERR_INVALID_CONTENT_ID,
          s"Content is not found for identifier: $id"
        )

      val metadata = node.getMetadata
      val status   = Option(metadata.get("status")).map(_.toString).getOrElse("")

      if (StringUtils.isBlank(status))
        throw new ClientException(
          "ERR_METADATA_ISSUE",
          "Content metadata error, status is blank for identifier: " + node.getIdentifier
        )
      request.getRequest.put("status", "PendingRetirement")
      request.getRequest.put("prevStatus", status)
      request.getRequest.put("versionKey", metadata.get("versionKey"))
      RequestUtil.restrictProperties(request)
      request.getContext.put("identifier", id)

      DataNode.update(request).map { updatedNode =>
        val identifier: String = updatedNode.getIdentifier.replace(".img", "")
        logger.info("Marked content as PendingRetirement for identifier: " + identifier)
        ResponseHandler.OK
          .put("node_id", identifier)
          .put("identifier", identifier)
      }
    }.flatMap(f => f) // flatten Future[Future[Response]] to Future[Response]
  }



//  def markContentPendingRetirement(request: Request)
//                                  (implicit ec: ExecutionContext,
//                                   oec: OntologyEngineContext): Future[Response] = {
//
//    val outerMap = request.getRequest
//    val reqMap = Option(outerMap.get("request"))
//      .map(_.asInstanceOf[java.util.Map[String, AnyRef]])
//      .getOrElse(throw new ClientException(
//        ContentConstants.ERR_INVALID_REQUEST,
//        "Request body is missing."
//      ))
//
//    val id = Option(reqMap.get(ContentConstants.CONTENT_ID))
//      .map(_.toString.trim)
//      .filter(StringUtils.isNotBlank)
//      .getOrElse(throw new ClientException(
//        ContentConstants.ERR_INVALID_CONTENT_ID,
//        ContentConstants.ERR_CONTENT_ID_MISSING
//      ))
//
//    val readReq = new Request()
//    readReq.setContext(new util.HashMap[String, AnyRef]() {{
//      put("graph_id", "domain")
//      put("version", "1.0")
//      put("objectType", "Content")
//      put("schemaName", "content")
//    }})
//    readReq.put("identifier", id)
//    readReq.setObjectType("Content")
//    readReq.put(ContentConstants.MODE, "read")
//
//    DataNode.read(readReq).flatMap { node =>
//      if (node == null)
//        throw new ClientException(
//          ContentConstants.ERR_INVALID_CONTENT_ID,
//          s"Content is not found for identifier: $id"
//        )
//
//      val metadata = node.getMetadata
//      val status   = Option(metadata.get("status")).map(_.toString).getOrElse("")
//
//      if (StringUtils.isBlank(status))
//        throw new ClientException(
//          "ERR_METADATA_ISSUE",
//          "Content metadata error, status is blank for identifier: " + node.getIdentifier
//        )
//
//      // mutate request for systemUpdate
//      request.getRequest.put("status", "PendingRetirement")
//      request.getRequest.put("prevStatus", status)
//      request.getRequest.put("versionKey", metadata.get("versionKey"))
//
//      RequestUtil.restrictProperties(request)
//      request.getContext.put("identifier", id)
//
//      // build nodeList for systemUpdate
//      val nodeList = new util.ArrayList[Node]()
//      nodeList.add(node)
//
//      val objectType =
//        Option(request.getContext.get("objectType"))
//          .map(_.asInstanceOf[String])
//          .getOrElse("Content")
//
//      // use systemUpdate instead of DataNode.update
//      val updateFut =
//        if (objectType.toLowerCase.equals("collection"))
//          DataNode.systemUpdate(request, nodeList, "content", Option(HierarchyManager.getHierarchy))
//        else
//          DataNode.systemUpdate(request, nodeList, "", None)
//
//      updateFut.map { updatedNode =>
//        val identifier: String = updatedNode.getIdentifier.replace(".img", "")
//        logger.info("Marked content as PendingRetirement for identifier: " + identifier)
//        ResponseHandler.OK
//          .put("node_id", identifier)
//          .put("identifier", identifier)
//      }
//    }
//  }



  private def createRetirementRequestRow(contentId: String,
                                         reqMap: java.util.Map[String, AnyRef]
                                        ): util.Map[String, AnyRef] = {

    val m = new util.HashMap[String, AnyRef]()

    // ExternalStore.insert uses "identifier" to set primaryKey(0) -> content_id
    m.put("identifier", contentId)

    // Table columns
    m.put("request_id", UUID.randomUUID().toString)

    // Reason & user
    val reason        = Option(reqMap.get("reason")).map(_.toString).getOrElse("")
    val userIdRaised  = Option(reqMap.get("userIdRaised")).map(_.toString).getOrElse("")

    m.put("reason_for_retirement", reason)
    m.put("user_id_raised", userIdRaised)

    // Parse dates from ISO string to Cassandra `date`
    val lastEnrollmentStr = Option(reqMap.get("lastEnrollmentDate")).map(_.toString)
    val retirementStr     = Option(reqMap.get("retirementDate")).map(_.toString)

    val isoFormatter = DateTimeFormatter.ISO_INSTANT

    def toLocalDateOpt(sOpt: Option[String]): Option[LocalDate] =
      sOpt.map { s =>
        val instant = Instant.from(isoFormatter.parse(s))
        LocalDate.fromMillisSinceEpoch(instant.toEpochMilli)
      }
    toLocalDateOpt(lastEnrollmentStr).foreach(d => m.put("last_enrollment_date", d))
    toLocalDateOpt(retirementStr).foreach(d => m.put("retirement_date", d))
    val now = new Date()
    m.put("created_at", now)
    m.put("updated_at", now)
    m.put("status", "Pending")
    m.put("approved", Boolean.box(false))
    m
  }

  private def insertRetirementRequest(contentId: String,
                                      reqMap: java.util.Map[String, AnyRef])
                                     (implicit ec: ExecutionContext): Future[Response] = {
    val rowMap = createRetirementRequestRow(contentId, reqMap)
    val propsMapping: Map[String, String] = Map.empty
    retirementRequestStore.insert(rowMap, propsMapping)
  }


  private def validateNoCbPlanForContent(contentId: String)
                                        (implicit ec: ExecutionContext): Future[Unit] = {

    // no columns needed just to check existence
    val extProps: List[String] = Nil
    val propsMapping: Map[String, String] = Map.empty

    read(contentId, extProps, propsMapping).map { resp =>
      val code = resp.getResponseCode // assuming Response has this
      code match {
        case ResponseCode.OK =>
          throw new ClientException(
            ContentConstants.ERR_CONTENT_HAS_ACTIVE_PLAN,
            "Content has cbPlan mappings and cannot be retired."
          )
        case ResponseCode.RESOURCE_NOT_FOUND =>
          ()
        case other =>
          throw new ServerException(
            ErrorCodes.ERR_SYSTEM_EXCEPTION.name,
            s"Error while checking cbPlan lookup. Response code: $other"
          )
      }
    }
  }


  def read(identifier: String, extProps: List[String], propsMapping: Map[String, String])(implicit ec: ExecutionContext): Future[Response] = {
    val select = QueryBuilder.select()
    if(null != extProps && !extProps.isEmpty){
      extProps.foreach(prop => {
        if("blob".equalsIgnoreCase(propsMapping.getOrElse(prop, "")))
          select.fcall("blobAsText", QueryBuilder.column(prop)).as(prop)
        else
          select.column(prop).as(prop)
      })
    }
    val selectQuery = select.from("sunbird", "cb_plan_v2_content_lookup")
    val clause: Clause = QueryBuilder.eq(ContentConstants.CONTENT_ID, identifier)
    selectQuery.where.and(clause)
    try {
      val session: Session = CassandraConnector.getSession
      session.executeAsync(selectQuery).asScala.map(resultSet => {
        if (resultSet.iterator().hasNext) {
          val row = resultSet.one()
          val externalMetadataMap = extProps.map(prop => prop -> row.getObject(prop)).toMap
          val response = ResponseHandler.OK()
          import scala.collection.JavaConverters._
          response.putAll(externalMetadataMap.asJava)
          response
        } else {
          TelemetryManager.error("Entry is not found in cassandra for content with identifier: " + identifier)
          ResponseHandler.ERROR(ResponseCode.RESOURCE_NOT_FOUND, ResponseCode.RESOURCE_NOT_FOUND.code().toString, "Entry is not found in cassandra for content with identifier: " + identifier)
        }
      })
    } catch {
      case e: Exception =>
        e.printStackTrace()
        TelemetryManager.error("Exception Occurred While Reading The Record. | Exception is : " + e.getMessage, e)
        throw new ServerException(ErrorCodes.ERR_SYSTEM_EXCEPTION.name, "Exception Occurred While Reading The Record. Exception is : " + e.getMessage)
    }
  }

  implicit class RichListenableFuture[T](lf: ListenableFuture[T]) {
    def asScala : Future[T] = {
      val p = Promise[T]()
      Futures.addCallback(lf, new FutureCallback[T] {
        def onFailure(t: Throwable): Unit = p failure t
        def onSuccess(result: T): Unit    = p success result
      }, MoreExecutors.directExecutor())
      p.future
    }
  }

  private def validateNoParentCollection(request: Request)
                                        (implicit ec: ExecutionContext,
                                         oec: OntologyEngineContext): Future[Unit] = {

    val outerMap = request.getRequest
    val reqMap = Option(outerMap.get("request"))
      .map(_.asInstanceOf[java.util.Map[String, AnyRef]])
      .getOrElse(throw new ClientException(
        ContentConstants.ERR_INVALID_REQUEST,
        "Request body is missing."
      ))

    val contentId = Option(reqMap.get(ContentConstants.CONTENT_ID))
      .map(_.toString.trim)
      .filter(StringUtils.isNotBlank)
      .getOrElse(throw new ClientException(
        ContentConstants.ERR_INVALID_CONTENT_ID,
        ContentConstants.ERR_CONTENT_ID_MISSING
      ))

    val readReq = new Request()
    readReq.setContext(new util.HashMap[String, AnyRef]() {{
      put("graph_id", "domain")
      put("version", "1.0")
      put("objectType", "Content")
      put("schemaName", "content")
    }})
    readReq.put("identifier", contentId)
    readReq.setObjectType("Content")
    readReq.put(ContentConstants.MODE, "read")

    DataNode.read(readReq).map { node =>
      if (node == null) {
        throw new ClientException(
          ContentConstants.ERR_INVALID_CONTENT_ID,
          "Content is not found."
        )
      }

      val metadata = node.getMetadata
      metadata.get("parentCollections") match {
        case list: java.util.Collection[_] if !list.isEmpty =>
          throw new ClientException(
            ContentConstants.ERR_CONTENT_PART_OF_COLLECTION,
            ContentConstants.ERR_CONTENT_PART_OF_COLLECTION_MSG
          )
        case _ =>
          ()
      }
    }
  }




  private def getNodeToRetire(request: Request)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = DataNode.read(request).map(node => {
        if (StringUtils.equalsIgnoreCase("Retired", node.getMetadata.get(ContentConstants.STATUS).asInstanceOf[String]))
            throw new ClientException(ContentConstants.ERR_CONTENT_RETIRE, "Content with Identifier " + node.getIdentifier + " is already Retired.")
        node
    })

    private def validateRequest(request: Request) = {
        val contentId: String = request.get(ContentConstants.IDENTIFIER).asInstanceOf[String]
        if (StringUtils.isBlank(contentId))
            throw new ClientException(ContentConstants.ERR_INVALID_CONTENT_ID, "Please Provide Valid Content Identifier.")
    }

    private def validateRequestForContentRetirement(request: Request): Unit = {
      val outerMap = request.getRequest
      val reqMap = Option(outerMap.get("request"))
        .map(_.asInstanceOf[java.util.Map[String, AnyRef]])
        .getOrElse(throw new ClientException(
          ContentConstants.ERR_INVALID_REQUEST,
            "Request body is missing."
        ))
      val contentId = Option(reqMap.get(ContentConstants.CONTENT_ID))
        .map(_.toString.trim)
        .filter(StringUtils.isNotBlank)
        .getOrElse(throw new ClientException(
          ContentConstants.ERR_INVALID_CONTENT_ID,
          ContentConstants.ERR_CONTENT_ID_MISSING
        ))
      val reason = Option(reqMap.get(ContentConstants.REASON))
        .map(_.toString.trim)
        .filter(StringUtils.isNotBlank)
        .getOrElse(throw new ClientException(
          ContentConstants.ERR_INVALID_REASON,
          ContentConstants.ERR_MISSING_REASON
        ))
      val lastEnrollmentDate = Option(reqMap.get(ContentConstants.LAST_ENROLLMENT_DATE))
        .map(_.toString.trim)
        .filter(StringUtils.isNotBlank)
        .getOrElse(throw new ClientException(
          ContentConstants.ERR_LAST_ENROLLMENT_DATE,
          ContentConstants.ERR_MISSING_LAST_ENROLLMENT_DATE
        ))
      val retirementDate = Option(reqMap.get(ContentConstants.RETIREMENT_DATE))
        .map(_.toString.trim)
        .filter(StringUtils.isNotBlank)
        .getOrElse(throw new ClientException(
          ContentConstants.ERR_INVALID_RETIREMENT_DATE,
          ContentConstants.MISSING_RETIREMENT_DATE
        ))
      if (retirementDate <= lastEnrollmentDate)
        throw new ClientException(
          ContentConstants.ERR_INVALID_DATE_ORDER,
          ContentConstants.ERR_INVALID_DATE_ORDER_MSG
        )
    }

    private def updateNodesToRetire(request: Request, updateMetadataMap: util.Map[String, AnyRef])(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Response] = {
        RedisCache.delete(request.get(ContentConstants.IDENTIFIER).asInstanceOf[String])
        val updateReq = new Request(request)
        updateReq.put(ContentConstants.IDENTIFIERS, java.util.Arrays.asList(request.get(ContentConstants.IDENTIFIER).asInstanceOf[String], request.get(ContentConstants.IDENTIFIER).asInstanceOf[String] + HierarchyConstants.IMAGE_SUFFIX))
        updateReq.put(ContentConstants.METADATA, updateMetadataMap)
        DataNode.bulkUpdate(updateReq).map(node => ResponseHandler.OK())
    }


    private def handleCollectionToRetire(node: Node, request: Request, updateMetadataMap: Map[String, AnyRef])(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Response] = {
        if (StringUtils.equalsIgnoreCase(ContentConstants.COLLECTION_MIME_TYPE, node.getMetadata.get(ContentConstants.MIME_TYPE).asInstanceOf[String]) && finalStatus.contains(node.getMetadata.get(ContentConstants.STATUS))) {
            RedisCache.delete("hierarchy_" + node.getIdentifier)
            val req = new Request(request)
            req.getContext.put(ContentConstants.SCHEMA_NAME, ContentConstants.COLLECTION_SCHEMA_NAME)
            req.put(ContentConstants.IDENTIFIER, request.get(ContentConstants.IDENTIFIER))
            oec.graphService.readExternalProps(req, List(HierarchyConstants.HIERARCHY)).flatMap(resp => {
                val hierarchyString = resp.getResult.toMap.getOrElse(HierarchyConstants.HIERARCHY, "").asInstanceOf[String]
                if (StringUtils.isNotBlank(hierarchyString)) {
                    val hierarchyMap = JsonUtils.deserialize(hierarchyString, classOf[util.HashMap[String, AnyRef]])
                    val childIds = getChildrenIdentifiers(hierarchyMap)
                    if (CollectionUtils.isNotEmpty(childIds)) {
                        val topicName = Platform.getString("kafka.topics.graph.event", "sunbirddev.learning.graph.events")
                        childIds.foreach(id => kfClient.send(ScalaJsonUtils.serialize(getLearningGraphEvent(request, id)), topicName))
                        RedisCache.delete(childIds.map(id => "hierarchy_" + id): _*)
                    }
                    hierarchyMap.putAll(updateMetadataMap)
                    req.put(HierarchyConstants.HIERARCHY, ScalaJsonUtils.serialize(hierarchyMap))
                    oec.graphService.saveExternalProps(req)
                } else Future(ResponseHandler.OK())
            }) recover { case e: ResourceNotFoundException =>
                TelemetryManager.log("No hierarchy is present in cassandra for identifier:" + node.getIdentifier)
                throw new ServerException("ERR_CONTENT_RETIRE", "Unable to fetch Hierarchy for Root Node: [" + node.getIdentifier + "]")
            }
        } else Future(ResponseHandler.OK())
    }


    private def getChildrenIdentifiers(hierarchyMap: util.HashMap[String, AnyRef]): util.List[String] = {
        val childIds: ListBuffer[String] = ListBuffer[String]()
        addChildIds(hierarchyMap.getOrElse(HierarchyConstants.CHILDREN, new util.ArrayList[util.HashMap[String, AnyRef]]()).asInstanceOf[util.ArrayList[util.HashMap[String, AnyRef]]], childIds)
        bufferAsJavaList(childIds)
    }

    private def addChildIds(childrenMaps: util.ArrayList[util.HashMap[String, AnyRef]], childrenIds: ListBuffer[String]): Unit = {
        if (CollectionUtils.isNotEmpty(childrenMaps)) {
            childrenMaps.filter(child => StringUtils.equalsIgnoreCase(HierarchyConstants.PARENT, child.get(HierarchyConstants.VISIBILITY).asInstanceOf[String])).foreach(child => {
                childrenIds += child.get(HierarchyConstants.IDENTIFIER).asInstanceOf[String]
                addChildIds(child.get(HierarchyConstants.CHILDREN).asInstanceOf[util.ArrayList[util.HashMap[String, AnyRef]]], childrenIds)
            })
        }
    }

    private def getLearningGraphEvent(request: Request, id: String): Map[String, Any] = Map("ets" -> System.currentTimeMillis(), "channel" -> request.getContext.get(ContentConstants.CHANNEL), "mid" -> UUID.randomUUID.toString, "nodeType" -> "DATA_NODE", "userId" -> "Ekstep", "createdOn" -> DateUtils.format(new Date()), "objectType" -> "Content", "nodeUniqueId" -> id, "operationType" -> "DELETE", "graphId" -> request.getContext.get("graph_id"))

}
