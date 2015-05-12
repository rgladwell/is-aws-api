// Copyright 2015 Ricardo Gladwell.
// Licensed under the GNU Affero General Public License.
// See the LICENSE file for more information.

package me.gladwell.aws

import org.specs2.mutable.{After, Specification}
import dispatch.classic._
import java.net.InetAddress
import unfiltered.specs2.jetty.Served
import org.specs2.specification.Scope
import unfiltered.specs2.Hosted
import javax.servlet.Filter
import scala.util.{Success, Failure}
import org.specs2.mock.Mockito
import scala.io.Source
import org.specs2.matcher.XmlMatchers
import scala.xml.XML
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl

object ApiSpec extends Specification with Mockito with XmlMatchers {

  import dispatch._

  val hostedIpAddress =  mock[InetAddress]
  val unhostedIpAddress =  mock[InetAddress]

  trait MockNetwork extends Network {
    case class MockIpPrefix(range: InetAddress) extends IpPrefix {
      def inRange(address: InetAddress): Boolean = (address == range)
    }

    override val ipRanges = mock[IpRangeLoader]
  }

  trait MockDns extends Dns {
    override val resolve = mock[Resolver]
  }

  trait ServedScope extends Hosted with Scope with After {
    this: Filter =>

    import unfiltered.jetty._

    lazy val server = Server.http(port).plan(this)

    server.start()
    
    def after = {
      server.stop()
      server.destroy()
    }
  }

  trait TestApiScope extends Api
    with HtmlViews
    with MockNetwork
    with MockDns
    with ServedScope {

    def endpoint = url(s"http://localhost:$port")
  }

  "The HTTP API" should {
    "return OK response for an index request" in new TestApiScope {
      status(endpoint) must_== 200
    }

    "return OK response for an address lookup" in new TestApiScope {
      resolve("hosted") returns Success(hostedIpAddress)
      resolve("unhosted") returns Success(unhostedIpAddress)
      ipRanges.apply() returns Success(Seq(MockIpPrefix(hostedIpAddress)))

      status(endpoint / "?address=unhosted") must_== 200
    }

    "return hosted view for hosted address" in new TestApiScope {
      resolve("hosted") returns Success(hostedIpAddress)
      resolve("unhosted") returns Success(unhostedIpAddress)
      ipRanges.apply() returns Success(Seq(MockIpPrefix(hostedIpAddress)))

      html(body(endpoint / "?address=hosted")) must \\("span", "id" -> "is-aws") \> "true"
    }

    "return unhosted view for unhosted address" in new TestApiScope {
      resolve("hosted") returns Success(hostedIpAddress)
      resolve("unhosted") returns Success(unhostedIpAddress)
      ipRanges.apply() returns Success(Seq(MockIpPrefix(hostedIpAddress)))

      html(body(endpoint / "?address=unhosted")) must \\("span", "id" -> "is-aws") \> "false"
    }

    "return error view for error on DNS lookup" in new TestApiScope {
      resolve(any) returns Failure(new RuntimeException("mock exception"))
      ipRanges.apply() returns Success(Seq(MockIpPrefix(hostedIpAddress)))

       html(body(endpoint / "?address=unhosted")) must \\("div", "id" -> "error")
    }

    "return error view for error on aquiring network IP range" in new TestApiScope {
      resolve("hosted") returns Success(hostedIpAddress)
      resolve("unhosted") returns Success(unhostedIpAddress)
      ipRanges.apply() returns Failure(new RuntimeException("mock exception"))

       html(body(endpoint / "?address=unhosted")) must \\("div", "id" -> "error")
    }
  }

  def GET(request: Request): Unit = status(request)

  def status(request: Request) = new Http x (request as_str) {
    case (code, _, _, _) => code
  }

  def body(request: Request): String = new Http x (request as_str) {
    case (_, response, _, _) => {
      val content = response.getEntity.getContent
      Source.fromInputStream(content).getLines().mkString("\n")
    }
  }
 
  def html(content: String) = {
    val parser = XML.withSAXParser(new SAXFactoryImpl().newSAXParser())
    parser.loadString(content)
  }

}
