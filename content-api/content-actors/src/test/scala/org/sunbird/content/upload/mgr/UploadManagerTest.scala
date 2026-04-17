package org.sunbird.content.upload.mgr

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util
import org.sunbird.graph.dac.model.Node
import scala.concurrent.ExecutionContext.Implicits.global


class UploadManagerTest extends AnyFlatSpec with Matchers {

	"getUploadResponse with valid node object" should "return response with artifactUrl" in {
		val node = new Node()
		node.setIdentifier("do_1234")
		node.setMetadata(new util.HashMap[String, AnyRef](){{
			put("artifactUrl", "testurl")
			put("versionKey",123456.asInstanceOf[AnyRef])
		}})
		val response = UploadManager.getUploadResponse(node)
		val result = response.getResult
		assert(null != response)
		assert("OK"==response.getResponseCode.toString)
		assert(result.size()==5)
		assert(result.get("artifactUrl").toString.equals("testurl"))
	}
}
