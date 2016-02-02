package com.sumologic.elasticsearch.util

import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.{Calendar, TimeZone}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import com.amazonaws.auth.AWSCredentials
import com.sumologic.elasticsearch.restlastic.RequestSigner
import spray.http.HttpHeaders.{Host, RawHeader}
import spray.http.HttpRequest

/**
 * Sign AWS requests following the instructions at http://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
 * @param awsCredentials AWS creds to sign with
 * @param region Region to connect to
 * @param service Service to connect to (eg. "es" for elasticsearch) [http://docs.aws.amazon.com/general/latest/gr/rande.html]
 */
class AwsRequestSigner(awsCredentials: AWSCredentials, region: String, service: String) extends RequestSigner {
  val Algorithm = "AWS4-HMAC-SHA256"

  def withAuthHeader(httpRequest: HttpRequest): HttpRequest = {
    val (dateTimeStr, dateStr) = currentDateStrings

    val withDateAndHost = completedRequest(httpRequest, dateTimeStr)

    val headerValue = s"$Algorithm Credential=${awsCredentials.getAWSAccessKeyId}/${credentialScope(dateStr)}, SignedHeaders=${signedHeaders(withDateAndHost)}" +
      s", Signature=${signature(withDateAndHost, dateTimeStr, dateStr)}"
    withDateAndHost.withHeaders(RawHeader("Authorization", headerValue) :: withDateAndHost.headers)
  }

  private [util] def completedRequest(httpRequest: HttpRequest, dateTimeStr: String): HttpRequest = {
    // Add a date and host header, but only if they aren't already there
    val dateHeader = if (httpRequest.headers.exists(header => Set(xAmzDate.toLowerCase, "date").contains(header.name.toLowerCase))) {
      List()
    } else {
      val dateHeader = RawHeader(xAmzDate, dateTimeStr)
      List(dateHeader)
    }

    val hostHeader = if (httpRequest.headers.exists(_.name.toLowerCase == "host")) {
      List()
    } else {
      val hostHeader = Host(httpRequest.uri.authority.host.address)
      List(hostHeader)
    }
    httpRequest.withHeaders(dateHeader ++ hostHeader ++  httpRequest.headers)
  }

  private[util] def stringToSign(httpRequest: HttpRequest, dateTimeStr: String, dateStr: String): String = {
    s"$Algorithm\n" +
      s"$dateTimeStr\n" +
      s"${credentialScope(dateStr)}\n" +
      s"${canonicalRequestHash(httpRequest)}"
  }

  private[util] def createCanonicalRequest(httpRequest: HttpRequest): String = {
    f"${httpRequest.method.name}\n" +
      f"${canonicalUri(httpRequest)}\n" +
      f"${canonicalQueryString(httpRequest)}\n" +
      f"${canonicalHeaders(httpRequest)}\n" +
      f"${signedHeaders(httpRequest)}\n" +
      f"${hexOf(hashedPayloadByteArray(httpRequest))}"
  }


  // Protected so that it can be overridden in tests
  protected def currentDateStrings: (String, String) = {
    val cal = Calendar.getInstance()
    val dfmT = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'")
    dfmT.setTimeZone(TimeZone.getTimeZone("GMT"))
    val dateTimeStr = dfmT.format(cal.getTime)
    val dfmD = new SimpleDateFormat("yyyyMMdd")
    dfmD.setTimeZone(TimeZone.getTimeZone("GMT"))
    val dateStr = dfmD.format(cal.getTime)
    (dateTimeStr, dateStr)
  }

  val xAmzDate = "X-Amz-Date"
  private def signature(httpRequest: HttpRequest, dateTimeStr: String, dateStr: String): String = {
    val signature = hmacSHA256(stringToSign(httpRequest, dateTimeStr, dateStr), signingKey(dateStr))
    hexOf(signature)
  }

  private def signingKey(dateStr: String): Array[Byte] = {
    val kSecret = ("AWS4" + awsCredentials.getAWSSecretKey).getBytes("UTF8")
    val kDate = hmacSHA256(dateStr, kSecret)
    val kRegion = hmacSHA256(region, kDate)
    val kService = hmacSHA256(service, kRegion)
    hmacSHA256("aws4_request", kService)
  }


  private def credentialScope(dateStr: String) = {
    s"$dateStr/$region/$service/aws4_request"
  }

  private def canonicalRequestHash(httpRequest: HttpRequest) = {
    val canonicalRequest = createCanonicalRequest(httpRequest)
    hexOf(hashSha256(canonicalRequest))
  }


  private def canonicalUri(httpRequest: HttpRequest): String = {
    val path = httpRequest.uri.path.toString()
    val segments = path.split("/")
    segments.filter(_ != "").map(urlEncode).mkString(start = "/", sep = "/", end = "")
  }

  private def urlEncode(s: String): String = URLEncoder.encode(s, "utf-8")

  private def canonicalQueryString(httpRequest: HttpRequest): String = {
    val params = httpRequest.uri.query
    val sortedEncoded = params.toList.map(kv => (urlEncode(kv._1), urlEncode(kv._2))) .sortBy(_._1)
    sortedEncoded.map(kv => s"${kv._1}=${kv._2}").mkString("&")
  }

  private def canonicalHeaders(httpRequest: HttpRequest): String = {
    httpRequest.headers.sortBy(_.name.toLowerCase).map(header => s"${header.name.toLowerCase}:${header.value.trim}").mkString("\n") + "\n"
  }

  private def signedHeaders(httpRequest: HttpRequest): String = {
    httpRequest.headers.map(_.name.toLowerCase).sorted.mkString(";")
  }

  private def hashedPayloadByteArray(httpRequest: HttpRequest): Array[Byte] = {
    val data = httpRequest.entity.asString
    hashSha256(data)
  }

  private def hashSha256(v: String): Array[Byte] = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(v.getBytes("UTF-8"))
    val digest = md.digest()
    digest
  }


  private def hmacSHA256(data: String, key: Array[Byte]): Array[Byte] = {
    val algorithm = "HmacSHA256"
    val mac = Mac.getInstance(algorithm)
    mac.init(new SecretKeySpec(key, algorithm))
    mac.doFinal(data.getBytes("UTF8"))
  }

  private def hexOf(buf: Array[Byte]) = buf.map("%02X" format _).mkString.toLowerCase
}