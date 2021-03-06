package models

import play.api.data.validation.ValidationError
import play.api.libs.json._

import play.api.mvc.WebSocket.FrameFormatter
import play.api.libs.functional.syntax._

// Notes...
// User can request:
// - registration
// - login
// - logout
// - access to a chat room (server will forward messages directed to chat room
// at socket).
// - leave chat room.
// - send a message to a chat room

//  server can request:
// - notification at chat room.
// - message from user.
// - reply with new token.
// - deny access(for login and registration), with reason why being given.

/** Represents a resource being requested/sent over websockets */
sealed trait ProtocolMsg

sealed trait ProtoReq extends ProtocolMsg
sealed trait ProtoRes extends ProtocolMsg

object ProtocolMsg {
	
	implicit class writesAdhocks[A](wr: OWrites[A]){
		def typeHint(kvs: (String, String)*) = wr.transform { js: JsValue =>
			js match {
				case JsObject(seq) => 
					val merged = seq ++ kvs.map { case(k, v) => (k, JsString(v)) }
					
					JsObject(merged)
				case other => other
			}
		}
	}
	
	/** Server-to-client writes
	 */
	
	val userMessageWrite = Json
			.writes[UserMessage]
			.typeHint("resource" -> "user-message", "code" -> "ok")
		
	val notificationWrite = Json
			.writes[Notification]
			.typeHint("resource" -> "notification", "code" -> "ok")
		
	val protocolErrorWrite = Json
			.writes[ProtocolError]
			.typeHint("code" -> "error")
		
	val protocolOkWrite = Json
			.writes[ProtocolOk]
			.typeHint("code" -> "ok")
		
	val userIdentityWrite = Json
			.writes[UserIdentity]
			.typeHint("resource" -> "identity", "code" -> "ok")
	
	val imageSubmissionWrite = Json
			.writes[ImageSubmission]
			.typeHint("resource" -> "image", "code" -> "ok")

	val imageSubmissionInitWrite = Json
		.writes[ImageSubmissionInit]
		.typeHint("resource" -> "image-init", "code" -> "ok")
			
	/** Client-to-server reads
	 */
	
	val registrationReqRead = Json
			.reads[RegistrationReq]
		
	val loginReqRead = Json
			.reads[LoginReq]
		
	val logoutReqRead = Json
			.reads[LogoutReq]
		
	val chatReqRead = Json
			.reads[ChatReq]
		
	val roomReqRead = Json
			.reads[RoomReq]
	
	val identityReqRead = Json
			.reads[IdentityReq]
			
	val disconnectReqRead = Json
		.reads[DisconnectReq]
	
	val imageReqRead = Json
		.reads[ImageReq]

	val imageInitReqRead = Json
		.reads[ImageReqInit]

	/**
	 * This is the final formatter which will be executed automatically at the
	 * end/beginning of the pipe.
	 */
		
	implicit def protocolMsgFormat: Format[ProtocolMsg] = Format(
			(__ \ "resource").read[String].flatMap {
				case "registration" => registrationReqRead.map(identity)
				case "login" => loginReqRead.map(identity)
				case "logout" => logoutReqRead.map(identity)
				case "chat-message" => chatReqRead.map(identity)
				case "room" => roomReqRead.map(identity)
				case "identity" => identityReqRead.map(identity)
				case "ping" => Reads[ProtocolMsg] { _ => JsSuccess(Ping) }
				case "disconnect" => disconnectReqRead.map(identity)
				case "image" => imageReqRead.map(identity)
				case "image-init" => imageInitReqRead.map(identity)
				case _ => Reads { _ => JsError("Format is invalid.") }
		},
		Writes {
			case nt: Notification => notificationWrite.writes(nt)
			case msg: UserMessage => userMessageWrite.writes(msg)
			case err: ProtocolError => protocolErrorWrite.writes(err)
			case ok: ProtocolOk => protocolOkWrite.writes(ok)
			case id: UserIdentity => userIdentityWrite.writes(id)
			case Ping => 
				val seq = Seq(
					("resource", JsString("ping")),
					("code", JsString("ok"))
				)
				JsObject(seq)
			case img: ImageSubmission => imageSubmissionWrite.writes(img)
			case imgInit: ImageSubmissionInit => imageSubmissionInitWrite.writes(imgInit)
			case _ => Json.obj("error" -> "Json writes not implemented.")
			
		}
	)
	
	implicit def protocolMsgFrameFormatter: FrameFormatter[ProtocolMsg] = 
		FrameFormatter.jsonFrame.transform(
      protocolMsg => Json.toJson(protocolMsg),
      json => Json.fromJson[ProtocolMsg](json).fold({ invalid =>
				val msg: String = invalid.map { case (path, errors) =>
					val errList = errors.map { error =>
							s" - ${error.message}(${error.args.mkString(",")})"
					}.mkString("\n")
					s"""Path: $path
						 |Errors:
						 |$errList
					 """.stripMargin
				}.mkString("\n")
				throw new RuntimeException("Bad client event on WebSocket: \n" +
					msg + "\n" +
					"Json submitted: " + json.toString())
			}, identity)
    )
	
}

/** Two way objects...
 */
case object Ping extends ProtocolMsg with ProtoReq with ProtoRes

/** Client-to-server definitions.
 * 
 *  Implicit formats are stored in the Implicits package object in the Models 
 *  file.
 */

// If Option is None, then disconnect from all rooms.
case class DisconnectReq(tokens: List[String], room: Option[String]) extends ProtoReq

case class RegistrationReq(name: String, password: String) extends ProtoReq

case class LoginReq(name: String, password: String) extends ProtoReq

case class LogoutReq(tokens: List[String]) extends ProtoReq

case class RoomReq(room: String) extends ProtoReq

case class ChatReq(room: String, content: String) extends ProtoReq

case class IdentityReq(tokens: List[String], withAllTokens: Option[Boolean] = None) extends ProtoReq

/** Image is submitted using base64 format */
case class ImageReq(id: String, part: Int, room: String, data: String) extends ProtoReq

case class ImageReqInit(id: String, room: String, parts: Int) extends ProtoReq

/** Server-to-client definitions.
	*
	* These are the responses that the server will send to the server.
	*/

case class UserMessage(userName: String, room: String, content: String) extends ProtoRes

// e.g., User "x" logs into the room.
case class Notification(room: String, content: String) extends ProtoRes

//Resource will correspond to the code the client sent. Reason
//is the exact GUI response that the user will see.
case class ProtocolError(resource: String, reason: String) extends ProtoRes

//Ok response will return a result, for example a token for the login.
case class ProtocolOk(resource: String, content: String) extends ProtoRes

case class UserIdentity(name: String, tokens: Option[List[String]] = None) extends ProtoRes

//case class ImageSubmission(name: String, room: String, content: String) extends ProtocolMsg

/** The client is sent an initial message to prepare for the image to be submitted */
case class ImageSubmissionInit(userName: String, id: String, room: String, parts: Int) extends ProtoRes

case class ImageSubmission(name: String, id: String, part: Int, room: String, data: String) extends ProtoRes
