package controllers.v1

import akka.actor.ActorRef
import controllers.BaseController
import play.api.mvc.ControllerComponents
import utils.{ActorNames, ApiId, QuestionSetOperations}

import javax.inject.{Inject, Named}
import scala.concurrent.ExecutionContext

/**
 * v1 QuestionSet API's.
 *
 * Currently holds only `publish`, which behaves like [[controllers.v4.QuestionSetController.publish]]
 * with one addition: the QuestionSet's own `createdFor` is also checked against the caller's
 * organisation (see AssessmentManager.getValidatedNodeForPublishWithOrgCheck).
 */
class QuestionSetController @Inject()(@Named(ActorNames.QUESTION_SET_ACTOR) questionSetActor: ActorRef, cc: ControllerComponents)(implicit exec: ExecutionContext) extends BaseController(cc) {

	val objectType = "QuestionSet"
	val schemaName: String = "questionset"
	val version = "1.0"

	def publish(identifier: String) = Action.async { implicit request =>
		val headers = commonHeaders()
		val body = requestBody()
		val questionSet = body.getOrDefault("questionset", new java.util.HashMap()).asInstanceOf[java.util.Map[String, Object]]
		questionSet.putAll(headers)
		val questionSetRequest = getRequest(questionSet, headers, QuestionSetOperations.publishQuestionSetOrgScoped.toString)
		setRequestContext(questionSetRequest, version, objectType, schemaName)
		questionSetRequest.getContext.put("identifier", identifier)

		// Caller's org, used by the external createdFor check - not part of commonHeaders() since
		// that mapping is shared with every other action in this service.
		val orgId = request.headers.get("x-authenticated-user-orgid").getOrElse("")
		questionSetRequest.getContext.put("orgId", orgId)

		getResult(ApiId.PUBLISH_QUESTION_SET_V1, questionSetActor, questionSetRequest)
	}
}
